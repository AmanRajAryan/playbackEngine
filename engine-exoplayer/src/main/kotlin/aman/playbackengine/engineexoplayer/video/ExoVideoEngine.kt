package aman.playbackengine.engineexoplayer.video

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import aman.playbackengine.enginecore.*
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [PlaybackEngine] implementation for video using a dual-item buffering strategy.
 * 
 * To ensure gapless video transitions, this engine pre-loads the next media item into the 
 * native player's queue whenever autoplay is enabled. This allows the native decoders to 
 * warm up the next stream before the current one finishes. 
 * 
 * The engine uses constant-time (O(1)) playlist updates via targeted [MediaItem] injection 
 * to maintain high performance, and relies on native [Player.STATE_ENDED] support 
 * when autoplay is disabled.
 */
@UnstableApi
class ExoVideoEngine(context: Context) : VideoEngine {

    override val type: EngineType = EngineType.EXOPLAYER
    private val TAG = "PB_VIDEO_EXO"
    private val player = ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build()
    private val bridgePlayer = ExoVideoBridgePlayer(Looper.getMainLooper())
    
    private val _playbackState = MutableStateFlow<EngineState>(EngineState.Idle)
    override val playbackState: StateFlow<EngineState> = _playbackState.asStateFlow()

    private val _playWhenReady = MutableStateFlow(false)
    override val playWhenReady: StateFlow<Boolean> = _playWhenReady.asStateFlow()

    override var onMediaItemTransition: (() -> Unit)? = null

    // --- Internal State Management ---
    private var masterPlaylist = mutableListOf<PlayableMedia>()
    private var currentMediaUid: String? = null
    private var isAutoplayEnabled = true
    private var currentRepeatMode = RepeatMode.OFF

    init {
        // Disable internal audio focus handling. 
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player.setAudioAttributes(audioAttributes, false)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                _playWhenReady.value = player.playWhenReady
                val newState = when (state) {
                    Player.STATE_IDLE -> EngineState.Idle
                    Player.STATE_BUFFERING -> EngineState.Buffering
                    Player.STATE_READY -> if (player.isPlaying) EngineState.Playing else EngineState.Paused
                    Player.STATE_ENDED -> EngineState.Ended
                    else -> EngineState.Idle
                }
                
                if (_playbackState.value != newState) {
                    _playbackState.value = newState
                    bridgePlayer.invalidate()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playWhenReady.value = player.playWhenReady
                val newState = if (player.playbackState == Player.STATE_READY) {
                    if (isPlaying) EngineState.Playing else EngineState.Paused
                } else {
                    _playbackState.value
                }

                if (_playbackState.value != newState) {
                    _playbackState.value = newState
                    bridgePlayer.invalidate()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _playbackState.value = EngineState.Error(error.message ?: "Exo Error", error)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    // Native REPEAT: No UID change, just notify controller to reset position if needed
                    onMediaItemTransition?.invoke()
                    bridgePlayer.invalidate()
                    return
                }

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // Native player moved automatically
                    val oldIndex = getCurrentMediaIndex()
                    var newIndex = oldIndex + 1
                    
                    // Handle Wrap-around for Repeat ALL
                    if (currentRepeatMode == RepeatMode.ALL && newIndex >= masterPlaylist.size) {
                        newIndex = 0
                    }

                    if (newIndex in masterPlaylist.indices) {
                        currentMediaUid = masterPlaylist[newIndex].uid
                        onMediaItemTransition?.invoke()
                        
                        // Advance the native playlist window
                        if (player.mediaItemCount > 1) {
                            player.removeMediaItem(0)
                        }
                        syncNativeWindow()
                    }
                }
                bridgePlayer.invalidate()
            }
        })
    }

    override fun getMedia3Player(): Player = bridgePlayer

    override fun prepare(mediaList: List<PlayableMedia>, startIndex: Int, startPositionMs: Long, autoPlay: Boolean) {
        val targetMedia = mediaList.getOrNull(startIndex)
        
        // --- State Restoration Optimization ---
        // If we already have the right media loaded, don't reset the whole player.
        // This prevents the "blank screen flash" when resuming from Stop.
        if (masterPlaylist == mediaList && currentMediaUid == targetMedia?.uid && player.mediaItemCount > 0) {
            PlaybackLogger.log(TAG, "ExoVideo: Soft resume detected. Skipping setMediaItems.")
            player.prepare()
            player.seekTo(0, startPositionMs)
            player.playWhenReady = autoPlay
            bridgePlayer.invalidate()
            return
        }

        masterPlaylist = mediaList.toMutableList()
        currentMediaUid = targetMedia?.uid
        
        val items = mutableListOf<MediaItem>()
        if (targetMedia != null) {
            items.add(mapMedia(targetMedia))
            val nextMedia = masterPlaylist.getOrNull(startIndex + 1)
            if (isAutoplayEnabled && nextMedia != null) {
                items.add(mapMedia(nextMedia))
            }
        }

        player.setMediaItems(items)
        player.prepare()
        player.seekTo(0, startPositionMs)
        player.playWhenReady = autoPlay
        
        // Re-apply volume with multiplier
        player.volume = currentBaseVolume * volumeMultiplier
        
        bridgePlayer.invalidate()
    }

    override fun prepare() {
        PlaybackLogger.log(TAG, "ExoVideo: Light prepare called.")
        player.prepare()
    }

    /**
     * Pre-loads the next item for gapless transitions.
     * Ensures native index 1 matches the next item in the master list.
     */
    private fun syncNativeWindow() {
        val nextIndexInMaster = getCurrentMediaIndex() + 1
        val nextInMaster = if (currentRepeatMode == RepeatMode.ALL && nextIndexInMaster >= masterPlaylist.size) {
            if (masterPlaylist.size > 1) masterPlaylist[0] else null
        } else {
            masterPlaylist.getOrNull(nextIndexInMaster)
        }

        if (isAutoplayEnabled && nextInMaster != null) {
            val nextMediaItem = mapMedia(nextInMaster)
            if (player.mediaItemCount > 1) {
                // Check if already correct to avoid redundant replacement
                if (player.getMediaItemAt(1).mediaId != nextMediaItem.mediaId) {
                    player.replaceMediaId(1, nextMediaItem)
                }
            } else {
                player.addMediaItem(nextMediaItem)
            }
        } else if (player.mediaItemCount > 1) {
            player.removeMediaItem(1)
        }
        bridgePlayer.invalidate()
    }

    private fun mapMedia(media: PlayableMedia): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.subtitle)
            .setArtworkUri(media.artworkUri)
            .setExtras(android.os.Bundle().apply { 
                putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, media.durationMs)
                media.extras.forEach { (key, value) -> putString(key, value) }
            })
            .build()

        return MediaItem.Builder()
            .setMediaId(media.uid) // USE UID FOR MEDIA3 IDENTITY
            .setUri(media.uri)
            .setMediaMetadata(metadata)
            .build()
    }

    override fun enqueueNext(media: PlayableMedia) {
        masterPlaylist.add(media)
        syncNativeWindow()
    }

    override fun addQueueItem(index: Int, media: PlayableMedia) {
        masterPlaylist.add(index, media)
        syncNativeWindow()
    }

    override fun removeQueueItem(index: Int) {
        if (index !in masterPlaylist.indices) return
        val itemToRemove = masterPlaylist[index]
        val isRemovingCurrent = itemToRemove.uid == currentMediaUid
        
        masterPlaylist.removeAt(index)

        if (isRemovingCurrent) {
            // Force reload current slot from new master list
            val currentPos = player.currentPosition
            val wasPlaying = player.playWhenReady
            val newIdx = index.coerceIn(masterPlaylist.indices)
            currentMediaUid = masterPlaylist.getOrNull(newIdx)?.uid
            
            if (currentMediaUid != null) {
                val items = mutableListOf<MediaItem>()
                items.add(mapMedia(masterPlaylist[newIdx]))
                val nextMedia = masterPlaylist.getOrNull(newIdx + 1)
                if (isAutoplayEnabled && nextMedia != null) {
                    items.add(mapMedia(nextMedia))
                }
                player.setMediaItems(items)
                player.prepare()
                player.seekTo(0, currentPos)
                player.playWhenReady = wasPlaying
            } else {
                player.clearMediaItems()
            }
        } else {
            syncNativeWindow()
        }
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in masterPlaylist.indices || toIndex !in masterPlaylist.indices) return
        val item = masterPlaylist.removeAt(fromIndex)
        masterPlaylist.add(toIndex, item)
        
        // No index math needed! Just sync the window based on currentMediaUid.
        syncNativeWindow()
    }

    override fun clearQueue() {
        masterPlaylist.clear()
        currentMediaUid = null
        player.clearMediaItems()
        bridgePlayer.invalidate()
    }

    override fun play() { player.play() }
    override fun pause() { player.pause() }
    override fun stop() { player.stop() }
    override fun replay() {

        player.seekTo(0)
        player.play()
    }

    private var volumeMultiplier = 1.0f
    private var currentBaseVolume = 1.0f

    override fun seekTo(positionMs: Long) { player.seekTo(positionMs) }
    
    override fun seekToItem(index: Int, positionMs: Long) {
        val currentIdx = getCurrentMediaIndex()
        if (index != currentIdx) {
            val media = masterPlaylist.getOrNull(index)
            currentMediaUid = media?.uid
            onMediaItemTransition?.invoke()
            
            if (media != null) {
                val items = mutableListOf<MediaItem>()
                items.add(mapMedia(media))
                val nextMedia = masterPlaylist.getOrNull(index + 1)
                if (isAutoplayEnabled && nextMedia != null) {
                    items.add(mapMedia(nextMedia))
                }
                player.setMediaItems(items)
                player.prepare()
                player.seekTo(0, positionMs)
                player.volume = currentBaseVolume * volumeMultiplier
            }
        } else {
            player.seekTo(positionMs)
        }
        bridgePlayer.invalidate()
    }

    override fun setVolume(volume: Float) { 
        currentBaseVolume = volume
        player.volume = (volume * volumeMultiplier).coerceAtMost(1.0f) 
    }
    override fun setVolumeMultiplier(multiplier: Float) {
        volumeMultiplier = multiplier
        // Note: ExoPlayer native volume is an attenuator (0.0 to 1.0). 
        // Software amplification (> 1.0) can be achieved via LoudnessEnhancer AudioEffect.
        player.volume = (currentBaseVolume * volumeMultiplier).coerceAtMost(1.0f)
    }
    override fun setAudioFocusEnabled(enabled: Boolean) {}

    override fun setRepeatMode(mode: RepeatMode) {
        currentRepeatMode = mode
        player.repeatMode = if (mode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        syncNativeWindow()
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters = player.playbackParameters.withSpeed(speed)
    }

    override fun setPlaybackPitch(pitch: Float) {
        player.playbackParameters = PlaybackParameters(player.playbackParameters.speed, pitch)
    }

    override fun setAutoplay(enabled: Boolean) {
        if (isAutoplayEnabled != enabled) {
            isAutoplayEnabled = enabled
            syncNativeWindow()
        }
    }

    override fun setDecoderPolicy(policy: DecoderPolicy) {
        PlaybackLogger.log(TAG, "ExoVideo: Setting Decoder Policy: $policy")
    }

    override fun getCurrentPosition(): Long = player.currentPosition
    override fun getDuration(): Long = player.duration.coerceAtLeast(0)
    
    override fun getCurrentMediaIndex(): Int {
        val uid = currentMediaUid ?: return 0
        return masterPlaylist.indexOfFirst { it.uid == uid }.coerceAtLeast(0)
    }

    override fun updatePlaylist(mediaList: List<PlayableMedia>, newCurrentIndex: Int) {
        PlaybackLogger.log(TAG, "ExoVideo: Seamlessly updating playlist (newIndex: $newCurrentIndex)")
        
        // 1. Update internal master list
        masterPlaylist = mediaList.toMutableList()
        
        // 2. The currentMediaUid remains the same, but its index in masterPlaylist is now newCurrentIndex.
        // syncNativeWindow() will look at index + 1 and update the native buffer (Index 1) if needed.
        syncNativeWindow()
        
        // 3. Update the MediaSession bridge
        bridgePlayer.invalidate()
    }

    override fun setVideoView(view: Any?) {
        PlaybackLogger.log(TAG, "ExoVideo: Setting video view: ${view?.javaClass?.simpleName}")
        when (view) {
            is PlayerView -> view.player = player
            is android.view.SurfaceView -> player.setVideoSurfaceView(view)
            is android.view.TextureView -> player.setVideoTextureView(view)
            else -> player.clearVideoSurface()
        }
    }

    override fun release() {
        player.release()
    }

    private fun ExoPlayer.replaceMediaId(index: Int, mediaItem: MediaItem) {
        this.replaceMediaItem(index, mediaItem)
    }

    private inner class ExoVideoBridgePlayer(looper: Looper) : SimpleBasePlayer(looper) {
        fun invalidate() { invalidateState() }

        override fun getState(): State {
            val commands = Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                    Player.COMMAND_SET_VOLUME, Player.COMMAND_GET_VOLUME
                ).build()

            val builder = State.Builder()
                .setAvailableCommands(commands)
                .setPlaybackState(player.playbackState)
                .setPlayWhenReady(player.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setContentPositionMs { player.currentPosition }
                .setPlaybackParameters(player.playbackParameters)

            if (masterPlaylist.isNotEmpty()) {
                val currentIdx = getCurrentMediaIndex()
                val playlistData = masterPlaylist.mapIndexed { index, media ->
                    val item = mapMedia(media)
                    val durationMs = if (index == currentIdx) player.duration.coerceAtLeast(0) else media.durationMs
                    MediaItemData.Builder(media.uid)
                        .setMediaItem(item)
                        .setMediaMetadata(item.mediaMetadata)
                        .setDurationUs(durationMs * 1000)
                        .build()
                }
                builder.setPlaylist(playlistData)
                builder.setCurrentMediaItemIndex(currentIdx)
            }
            return builder.build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            player.playWhenReady = playWhenReady
            invalidate()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(index: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            val currentIdx = getCurrentMediaIndex()
            if (index != currentIdx) {
                val media = masterPlaylist.getOrNull(index)
                currentMediaUid = media?.uid
                onMediaItemTransition?.invoke()
                
                if (media != null) {
                    val items = mutableListOf<MediaItem>()
                    items.add(mapMedia(media))
                    val nextMedia = masterPlaylist.getOrNull(index + 1)
                    if (isAutoplayEnabled && nextMedia != null) {
                        items.add(mapMedia(nextMedia))
                    }
                    player.setMediaItems(items)
                    player.prepare()
                    player.seekTo(0, positionMs)
                    player.volume = currentBaseVolume * volumeMultiplier
                }
            } else {
                player.seekTo(positionMs)
            }
            invalidate()
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            this@ExoVideoEngine.stop()
            invalidate()
            return Futures.immediateVoidFuture()
        }
    }
}
