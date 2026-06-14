package aman.playbackengine.engineexoplayer.audio

import android.content.Context
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import aman.playbackengine.enginecore.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlin.math.abs

/**
 * Professional [PlaybackEngine] implementation supporting seamless crossfading using two [ExoPlayer] instances.
 * 
 * This engine manages two players (primary and background) to perform smooth volume transitions
 * between tracks. When a track nears completion, the next track is prepared in the background
 * and a time-linked volume ramp is executed.
 */
@UnstableApi
class CrossfadeExoEngine(
    private val context: Context
) : AudioEngine {

    override val type: EngineType = EngineType.EXOPLAYER
    private val TAG = "CrossfadeExoEngine"
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // --- Reactive State Flows ---
    private val _playbackState = MutableStateFlow<EngineState>(EngineState.Idle)
    override val playbackState: StateFlow<EngineState> = _playbackState.asStateFlow()

    private val _playWhenReady = MutableStateFlow(false)
    override val playWhenReady: StateFlow<Boolean> = _playWhenReady.asStateFlow()

    /**
     * Diagnostic data for UI monitoring of the dual-player internals.
     */
    data class ExoDiagnostic(
        val currentVol: Float = 0f,
        val currentPos: Long = 0L,
        val backgroundVol: Float = 0f,
        val backgroundPos: Long = 0L,
        val isTransitioning: Boolean = false,
        val primaryIsPlayer1: Boolean = true
    )
    private val _diagnostics = MutableStateFlow(ExoDiagnostic())
    val diagnostics = _diagnostics.asStateFlow()

    private var player1 = ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build()
    private var player2 = ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build()
    
    private var currentPlayer = player1
    private var backgroundPlayer = player2

    private var playlist = mutableListOf<PlayableMedia>()
    private var currentMediaUid: String? = null
    private var isPlayingInternal = false
    
    private var transitionInProgress = false
    private var transitionJob: Job? = null
    private var currentRepeatMode = RepeatMode.OFF

    // --- Volume & Normalization state ---
    private var currentMultiplier = 1.0f
    private var nextMultiplier = 1.0f
    private var currentBaseVolume = 1.0f
    
    private var transitionStartTime = 0L
    private var activeTransitionDuration = 0L
    private var isFastForwardingTransition = false

    override var onMediaItemTransition: (() -> Unit)? = null

    private inner class SessionPlayer(looper: Looper) : SimpleBasePlayer(looper) {
        private var cachedPlaylistData = listOf<MediaItemData>()
        
        fun triggerInvalidate() { invalidateState() }

        fun rebuildPlaylistCache() {
            cachedPlaylistData = playlist.mapIndexed { index, media ->
                val mapped = mapMedia(media)
                MediaItemData.Builder(media.uid)
                    .setMediaItem(mapped)
                    .setMediaMetadata(mapped.mediaMetadata)
                    .setDurationUs(C.msToUs(media.durationMs.takeIf { it > 0 } ?: C.TIME_UNSET))
                    .build() 
            }
            PlaybackLogger.log(TAG, "Playlist Cache Rebuilt: ${cachedPlaylistData.size} items")
        }

        override fun getState(): State {
            val commandsBuilder = Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SET_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SET_VOLUME, Player.COMMAND_GET_VOLUME
                )

            val currentIdx = getCurrentMediaIndex()
            val builder = State.Builder()
                .setAvailableCommands(commandsBuilder.build())
                .setPlaybackState(currentPlayer.playbackState)
                .setPlayWhenReady(currentPlayer.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setContentPositionMs { currentPlayer.currentPosition }
                .setPlaybackParameters(currentPlayer.playbackParameters)

            if (cachedPlaylistData.isNotEmpty()) {
                builder.setPlaylist(cachedPlaylistData)
                builder.setCurrentMediaItemIndex(currentIdx.coerceIn(cachedPlaylistData.indices))
            }

            return builder.build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (playWhenReady) this@CrossfadeExoEngine.play() else this@CrossfadeExoEngine.pause()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            this@CrossfadeExoEngine.seekToItem(mediaItemIndex, positionMs)
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            return Futures.immediateVoidFuture()
        }
    }

    private val sessionPlayer = SessionPlayer(Looper.getMainLooper())

    init {
        setupPlayerListener(player1)
        setupPlayerListener(player2)

        scope.launch {
            while (true) {
                delay(200) 
                
                if (!transitionInProgress && isPlayingInternal && currentPlayer.playbackState == Player.STATE_READY) {
                    val remaining = currentPlayer.duration - currentPlayer.currentPosition
                    val currentIdx = getCurrentMediaIndex()
                    val hasNext = currentIdx + 1 < playlist.size || (currentRepeatMode == RepeatMode.ALL && playlist.size > 1)
                    
                    val duration = PlaybackManager.crossfadeDurationMs
                    if (currentRepeatMode != RepeatMode.ONE && currentPlayer.duration > 0 && remaining in 1..duration && hasNext) {
                        initiateCrossfade()
                    }
                }
                
                _diagnostics.value = ExoDiagnostic(
                    currentVol = currentPlayer.volume, currentPos = currentPlayer.currentPosition,
                    backgroundVol = backgroundPlayer.volume, backgroundPos = backgroundPlayer.currentPosition,
                    isTransitioning = transitionInProgress,
                    primaryIsPlayer1 = (currentPlayer === player1)
                )
                
                if (playlist.isNotEmpty() && currentPlayer.playbackState != Player.STATE_IDLE) {
                    sessionPlayer.triggerInvalidate()
                }
            }
        }
    }

    private fun initiateCrossfade() {
        val currentIdx = getCurrentMediaIndex()
        var nextIndex = currentIdx + 1
        if (currentRepeatMode == RepeatMode.ALL && nextIndex >= playlist.size) {
            nextIndex = 0
        }
        val nextMedia = playlist.getOrNull(nextIndex) ?: return
        
        PlaybackLogger.log(TAG, "Crossfade: Preparing background track: ${nextMedia.title}")
        transitionInProgress = true
        isFastForwardingTransition = false
        activeTransitionDuration = PlaybackManager.crossfadeDurationMs
        transitionStartTime = System.currentTimeMillis() // Initialize immediately for seek safety

        backgroundPlayer.setMediaItem(mapMedia(nextMedia))
        backgroundPlayer.volume = 0f
        backgroundPlayer.prepare()
        backgroundPlayer.playWhenReady = isPlayingInternal

        transitionJob?.cancel()
        transitionJob = scope.launch {
            // Wait for background player to be ready (Max 5s)
            val bufferStartTime = System.currentTimeMillis()
            while (backgroundPlayer.playbackState != Player.STATE_READY) {
                if (!transitionInProgress) return@launch
                if (System.currentTimeMillis() - bufferStartTime > 5000) {
                    PlaybackLogger.log(TAG, "Crossfade: Background buffer timeout. Forcing transition.")
                    break
                }
                delay(100)
            }

            // Verify target still exists
            if (!playlist.any { it.uid == nextMedia.uid }) {
                PlaybackLogger.log(TAG, "Crossfade: Target media removed. Aborting.")
                finalizeTransition()
                return@launch
            }

            // Swap roles
            val outgoingPlayer = currentPlayer
            val incomingPlayer = backgroundPlayer
            
            backgroundPlayer = outgoingPlayer
            currentPlayer = incomingPlayer
            currentMediaUid = nextMedia.uid
            
            // Sync multipliers
            val oldMultiplier = currentMultiplier
            currentMultiplier = nextMultiplier

            onMediaItemTransition?.invoke()
            sessionPlayer.triggerInvalidate()

            manageVolumeRamps(oldMultiplier, currentMultiplier)
        }
    }

    /**
     * Executes the volume crossfade between the outgoing and incoming players.
     * Uses linear ramps over the specified transition duration.
     */
    private suspend fun manageVolumeRamps(outgoingMultiplier: Float, incomingMultiplier: Float) {
        PlaybackLogger.log(TAG, "FadeCoordinator: Commencing volume transition.")
        
        while (transitionInProgress) {
            if (!isPlayingInternal) {
                // Pause the wall-clock timer while playback is paused
                transitionStartTime += 50
                delay(50)
                continue
            }

            val elapsed = System.currentTimeMillis() - transitionStartTime
            val duration = if (isFastForwardingTransition) 1000L else activeTransitionDuration
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            
            // Linear ramps using stable multipliers but reactive base volume
            val incomingVol = progress * incomingMultiplier * currentBaseVolume
            val outgoingVol = (1f - progress) * outgoingMultiplier * currentBaseVolume
            
            currentPlayer.volume = incomingVol
            backgroundPlayer.volume = outgoingVol

            if (progress >= 1f) break
            delay(50)
            if (!transitionInProgress) break
        }

        finalizeTransition()
    }

    private fun finalizeTransition() {
        transitionJob?.cancel()
        transitionJob = null
        backgroundPlayer.pause()
        backgroundPlayer.volume = 0f
        currentPlayer.volume = currentMultiplier * currentBaseVolume
        transitionInProgress = false
        isFastForwardingTransition = false
        PlaybackLogger.log(TAG, "Crossfade: Transition finalized.")
        sessionPlayer.triggerInvalidate()
    }

    private fun setupPlayerListener(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player === currentPlayer) {
                    _playWhenReady.value = player.playWhenReady
                    if (state == Player.STATE_ENDED && !transitionInProgress) {
                        val currentIdx = getCurrentMediaIndex()
                        var nextIdx = currentIdx + 1
                        if (currentRepeatMode == RepeatMode.ALL && nextIdx >= playlist.size) {
                            nextIdx = 0
                        }
                        seekToItem(nextIdx, 0)
                    }
                    updateLibraryState(state)
                    sessionPlayer.triggerInvalidate()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player === currentPlayer) {
                    _playWhenReady.value = player.playWhenReady
                    isPlayingInternal = isPlaying
                    updateLibraryState(player.playbackState)
                    sessionPlayer.triggerInvalidate()
                }
            }
        })
    }

    private fun updateLibraryState(exoState: Int) {
        _playbackState.value = when (exoState) {
            Player.STATE_IDLE -> EngineState.Idle
            Player.STATE_BUFFERING -> EngineState.Buffering
            Player.STATE_READY -> if (isPlayingInternal) EngineState.Playing else EngineState.Paused
            Player.STATE_ENDED -> EngineState.Ended
            else -> EngineState.Idle
        }
    }

    override fun getMedia3Player(): Player = sessionPlayer

    override fun prepare(mediaList: List<PlayableMedia>, startIndex: Int, startPositionMs: Long, autoPlay: Boolean) {
        if (mediaList.isEmpty() || startIndex !in mediaList.indices) return
        val media = mediaList[startIndex]
        
        if (playlist == mediaList && currentMediaUid == media.uid) {
            currentPlayer.prepare()
            currentPlayer.playWhenReady = autoPlay
            sessionPlayer.triggerInvalidate()
            return
        }

        stop()
        playlist = mediaList.toMutableList()
        sessionPlayer.rebuildPlaylistCache()
        currentMediaUid = media.uid
        
        currentPlayer.setMediaItem(mapMedia(media), startPositionMs)
        currentPlayer.prepare()
        currentPlayer.volume = currentBaseVolume * currentMultiplier
        currentPlayer.playWhenReady = autoPlay
        sessionPlayer.triggerInvalidate()
    }

    override fun prepare() {
        currentPlayer.prepare()
    }

    override fun stop() {
        transitionJob?.cancel()
        transitionInProgress = false
        player1.stop()
        player2.stop()
        player1.volume = 1f
        player2.volume = 0f
    }

    override fun enqueueNext(media: PlayableMedia) {
        playlist.add(media)
        sessionPlayer.rebuildPlaylistCache()
        sessionPlayer.triggerInvalidate()
    }

    override fun addQueueItem(index: Int, media: PlayableMedia) {
        playlist.add(index, media)
        sessionPlayer.rebuildPlaylistCache()
        sessionPlayer.triggerInvalidate()
    }

    override fun removeQueueItem(index: Int) {
        if (index !in playlist.indices) return
        val itemToRemove = playlist[index]
        val isRemovingCurrent = itemToRemove.uid == currentMediaUid
        
        playlist.removeAt(index)

        if (isRemovingCurrent) {
            val wasPlaying = isPlayingInternal
            stop()
            val newIdx = index.coerceIn(playlist.indices)
            val newMedia = playlist.getOrNull(newIdx)
            currentMediaUid = newMedia?.uid
            
            if (newMedia != null) {
                currentPlayer.setMediaItem(mapMedia(newMedia))
                currentPlayer.prepare()
                currentPlayer.playWhenReady = wasPlaying
            } else {
                _playbackState.value = EngineState.Idle
            }
        }
        
        sessionPlayer.rebuildPlaylistCache()
        sessionPlayer.triggerInvalidate()
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return
        val item = playlist.removeAt(fromIndex)
        playlist.add(toIndex, item)
        sessionPlayer.rebuildPlaylistCache()
        sessionPlayer.triggerInvalidate()
    }

    override fun clearQueue() {
        playlist.clear()
        currentMediaUid = null
        stop()
        sessionPlayer.rebuildPlaylistCache()
        sessionPlayer.triggerInvalidate()
    }

    override fun play() { 
        currentPlayer.play()
        if (transitionInProgress) backgroundPlayer.play()
    }
    
    override fun pause() { 
        currentPlayer.pause()
        if (transitionInProgress) backgroundPlayer.pause()
    }

    override fun replay() {
        currentPlayer.seekTo(0)
        currentPlayer.play()
    }
    
    override fun seekTo(positionMs: Long) {
        currentPlayer.seekTo(positionMs)
        
        if (transitionInProgress) {
            // Seek-Out Snap Logic: 
            // If the user seeks past the transition window, we accelerate the fade to finish in 1 second.
            if (positionMs > activeTransitionDuration) {
                if (!isFastForwardingTransition) {
                    PlaybackLogger.log(TAG, "Crossfade: User sought past window. Accelerating transition.")
                    isFastForwardingTransition = true
                    
                    // Maintain current progress but shorten total duration to 1s to prevent volume pop
                    val currentProgress = (System.currentTimeMillis() - transitionStartTime).toFloat() / activeTransitionDuration
                    transitionStartTime = System.currentTimeMillis() - (currentProgress * 1000L).toLong()
                }
            }
        }
        
        sessionPlayer.triggerInvalidate()
    }

    override fun seekToItem(index: Int, positionMs: Long) {
        if (index in playlist.indices) {
            val targetMedia = playlist[index]
            if (targetMedia.uid != currentMediaUid) {
                if (transitionInProgress) { 
                     finalizeTransition()
                }
                
                stop()
                currentMediaUid = targetMedia.uid
                currentPlayer.setMediaItem(mapMedia(playlist[index]), positionMs)
                currentPlayer.prepare()
                currentPlayer.volume = currentBaseVolume * currentMultiplier
                currentPlayer.play()
                onMediaItemTransition?.invoke()
            } else {
                seekTo(positionMs)
            }
        }
        sessionPlayer.triggerInvalidate()
    }

    override fun setVolume(volume: Float) { 
        currentBaseVolume = volume
        if (!transitionInProgress) {
            currentPlayer.volume = (volume * currentMultiplier).coerceAtMost(1.0f) 
        }
    }

    override fun setVolumeMultiplier(multiplier: Float) {
        if (transitionInProgress) {
            nextMultiplier = multiplier
        } else {
            currentMultiplier = multiplier
            currentPlayer.volume = (currentBaseVolume * currentMultiplier).coerceAtMost(1.0f)
        }
        PlaybackLogger.log(TAG, "Volume: Multiplier updated to $multiplier (Transition: $transitionInProgress)")
    }

    override fun setAudioFocusEnabled(enabled: Boolean) {}
    override fun setRepeatMode(mode: RepeatMode) {
        currentRepeatMode = mode
        val exoMode = if (mode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player1.repeatMode = exoMode
        player2.repeatMode = exoMode
    }
    
    override fun setPlaybackSpeed(speed: Float) {
        player1.playbackParameters = player1.playbackParameters.withSpeed(speed)
        player2.playbackParameters = player2.playbackParameters.withSpeed(speed)
    }

    override fun setPlaybackPitch(pitch: Float) {
        player1.playbackParameters = PlaybackParameters(player1.playbackParameters.speed, pitch)
        player2.playbackParameters = PlaybackParameters(player2.playbackParameters.speed, pitch)
    }

    override fun setAutoplay(enabled: Boolean) {}
    override fun getCurrentPosition(): Long = currentPlayer.currentPosition
    override fun getDuration(): Long = currentPlayer.duration.coerceAtLeast(0)
    
    override fun getCurrentMediaIndex(): Int {
        val uid = currentMediaUid ?: return 0
        return playlist.indexOfFirst { it.uid == uid }.coerceAtLeast(0)
    }

    override fun updatePlaylist(mediaList: List<PlayableMedia>, newCurrentIndex: Int) {
        playlist = mediaList.toMutableList()
        sessionPlayer.rebuildPlaylistCache()
        sessionPlayer.triggerInvalidate()
    }

    override fun release() {
        PlaybackLogger.log(TAG, "Releasing engine")
        transitionInProgress = false
        transitionJob?.cancel()
        player1.release()
        player2.release()
        scope.cancel()
    }

    private fun mapMedia(media: PlayableMedia): MediaItem {
        return MediaItem.Builder()
            .setMediaId(media.uid).setUri(media.uri)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(media.title).setDisplayTitle(media.title)
                .setArtist(media.subtitle ?: "Unknown").setAlbumArtist(media.subtitle ?: "Unknown")
                .setAlbumTitle(media.title).setArtworkUri(media.artworkUri)
                .setExtras(android.os.Bundle().apply { 
                    putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, media.durationMs)
                })
                .setIsPlayable(true).build())
            .build()
    }
}
