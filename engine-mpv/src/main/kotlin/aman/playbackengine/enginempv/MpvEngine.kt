package aman.playbackengine.enginempv

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import aman.playbackengine.enginecore.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import aman.playbackengine.enginempv.MpvBridgePlayer
import aman.playbackengine.enginempv.MpvCore
import aman.playbackengine.enginecore.equalizer.EqualizerManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * libmpv-based implementation of [PlaybackEngine].
 * 
 * This engine uses a singleton pattern for the native core to manage JNI constraints.
 * It features a "Grace Period" mechanism where the native core is kept alive for a 
 * period after release to allow for instant re-attachment without the overhead of 
 * native re-initialization.
 */
@UnstableApi
class MpvEngine private constructor(context: Context) : VideoEngine, AudioEngine, SurfaceHolder.Callback {

    override val type: EngineType = EngineType.MPV
    private val TAG = "MPV_ENGINE"
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    private val core = MpvCore(context.applicationContext)
    private val bridgePlayer = MpvBridgePlayer(Looper.getMainLooper(), core)

    private val _playbackState = MutableStateFlow<EngineState>(EngineState.Idle)
    override val playbackState: StateFlow<EngineState> = _playbackState.asStateFlow()
    override val playWhenReady: StateFlow<Boolean> = core.playWhenReady

    override var onMediaItemTransition: (() -> Unit)? = null
    override var onSubtitlesChanged: ((List<SubtitleTrack>) -> Unit)? = null
    override var onAudioTracksChanged: ((List<AudioTrack>) -> Unit)? = null
    private var lastEmittedIndex = -1
    private var currentMediaList = emptyList<PlayableMedia>()

    private var currentSurfaceView: SurfaceView? = null
    private var shutdownJob: Job? = null

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: MpvEngine? = null

        /**
         * Returns the singleton instance of the MPV engine.
         * Automatically cancels any pending shutdown timers.
         */
        fun getInstance(context: Context): MpvEngine {
            val eng = instance ?: synchronized(this) {
                instance ?: MpvEngine(context.applicationContext).also { instance = it }
            }
            eng.cancelShutdownTimer()
            return eng
        }

        /**
         * Force immediate destruction of the native core and clears the singleton.
         * Used during app shutdown or critical resource reclamation.
         */
        internal fun destroyInstance() {
            synchronized(this) {
                instance?.let { 
                    it.cancelShutdownTimer()
                    it.core.destroyBlocking()
                }
                instance = null
            }
        }
    }

    private fun cancelShutdownTimer() {
        shutdownJob?.cancel()
        shutdownJob = null
    }

    init {
        core.onTracksChanged = {
            val countStr = core.getPropertyString("track-list/count")
            val count = countStr?.toIntOrNull() ?: 0
            val subTracks = mutableListOf<SubtitleTrack>()
            val audioTracks = mutableListOf<AudioTrack>()
            for (i in 0 until count) {
                val type = core.getPropertyString("track-list/$i/type")
                if (type == "sub") {
                    val id = core.getPropertyString("track-list/$i/id") ?: continue
                    val lang = core.getPropertyString("track-list/$i/lang")
                    val title = core.getPropertyString("track-list/$i/title")
                    val selected = core.getPropertyString("track-list/$i/selected") == "yes"
                    subTracks.add(SubtitleTrack(id, lang, title, selected))
                } else if (type == "audio") {
                    val id = core.getPropertyString("track-list/$i/id") ?: continue
                    val lang = core.getPropertyString("track-list/$i/lang")
                    val title = core.getPropertyString("track-list/$i/title")
                    val selected = core.getPropertyString("track-list/$i/selected") == "yes"
                    audioTracks.add(AudioTrack(id, lang, title, selected))
                }
            }
            onSubtitlesChanged?.invoke(subTracks)
            onAudioTracksChanged?.invoke(audioTracks)
        }

        // Map MpvCore state to EngineState for the VideoController UI
        core.state.onEach { state ->
            _playbackState.value = when {
                state.isEnded -> EngineState.Ended
                state.isBuffering -> EngineState.Buffering
                state.isIdle -> EngineState.Idle
                state.isPaused -> EngineState.Paused
                else -> EngineState.Playing
            }
            
            if (state.currentIndex != lastEmittedIndex && state.playlist.isNotEmpty()) {
                lastEmittedIndex = state.currentIndex
                onMediaItemTransition?.invoke()
            }
        }.launchIn(scope)

        // Listen to Equalizer state
        scope.launch {
            EqualizerManager.enabled.collectLatest { enabled ->
                if (enabled) {
                    applyMpvEqualizer(EqualizerManager.bands.value)
                } else {
                    core.setPropertyString("af", "")
                }
            }
        }
        scope.launch {
            EqualizerManager.bands.collectLatest { bands ->
                if (EqualizerManager.enabled.value) {
                    applyMpvEqualizer(bands)
                }
            }
        }
    }

    private fun applyMpvEqualizer(bands: List<aman.playbackengine.enginecore.equalizer.EqBand>) {
        if (bands.isEmpty()) {
            core.setPropertyString("af", "")
            return
        }
        // Build FFmpeg lavfi filter string mirroring the hardware EqBands
        val filters = bands.joinToString(",") { band ->
            val hz = band.centerFreqHz / 1000.0
            val db = band.currentLevel / 100.0
            "equalizer=f=$hz:width_type=o:width=1.5:g=$db"
        }
        val filterString = "lavfi=[$filters]"
        core.setPropertyString("af", filterString)
    }

    override fun getMedia3Player(): Player = bridgePlayer

    override fun prepare(mediaList: List<PlayableMedia>, startIndex: Int, startPositionMs: Long, autoPlay: Boolean) {
        currentMediaList = mediaList
        cancelShutdownTimer()
        val mediaItems = mediaList.map { media ->
            val builder = MediaItem.Builder()
                .setMediaId(media.uid)
                .setUri(media.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(media.title)
                        .setArtworkUri(media.artworkUri)
                        .setExtras(android.os.Bundle().apply {
                            media.extras.forEach { (key, value) -> putString(key, value) }
                        })
                        .build()
                )
                
            media.externalSubtitleUri?.let { subUri ->
                val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subUri))
                    .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("en")
                    .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                    .build()
                builder.setSubtitleConfigurations(listOf(subtitleConfig))
            }
            
            builder.build()
        }
        lastEmittedIndex = startIndex
        core.loadPlaylist(mediaItems, startIndex, startPositionMs, autoPlay = autoPlay)
    }

    override fun prepare() {
        if (currentMediaList.isNotEmpty()) {
            val index = core.state.value.currentIndex
            val pos = core.state.value.positionMs
            prepare(currentMediaList, index, pos, autoPlay = false)
        }
    }

    override fun enqueueNext(media: PlayableMedia) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(media.uid)
            .setUri(media.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(media.title)
                    .setArtworkUri(media.artworkUri)
                    .setExtras(android.os.Bundle().apply {
                        media.extras.forEach { (key, value) -> putString(key, value) }
                    })
                    .build()
            )
            .build()
        core.append(mediaItem)
    }

    override fun addQueueItem(index: Int, media: PlayableMedia) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(media.uid)
            .setUri(media.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(media.title)
                    .setArtworkUri(media.artworkUri)
                    .setExtras(android.os.Bundle().apply {
                        media.extras.forEach { (key, value) -> putString(key, value) }
                    })
                    .build()
            )
            .build()
        core.addQueueItem(index, mediaItem)
    }

    override fun removeQueueItem(index: Int) {
        core.removeQueueItem(index)
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        core.moveQueueItem(fromIndex, toIndex)
    }

    override fun clearQueue() {
        core.clearQueue()
    }

    override fun setAutoplay(enabled: Boolean) {
        core.setAutoplay(enabled)
    }

    override fun setDecoderPolicy(policy: DecoderPolicy) {
        core.setDecoderPolicy(policy)
    }

    override fun selectSubtitleTrack(trackId: String?) {
        core.setPropertyString("sid", trackId ?: "no")
    }

    override fun selectAudioTrack(trackId: String) {
        core.setPropertyString("aid", trackId)
    }

    override fun setScaleMode(mode: ScaleMode) {
        when (mode) {
            ScaleMode.FIT -> {
                core.setPropertyString("keepaspect", "yes")
                core.setPropertyString("panscan", "0.0")
            }
            ScaleMode.FILL -> {
                core.setPropertyString("keepaspect", "yes")
                core.setPropertyString("panscan", "1.0")
            }
            ScaleMode.STRETCH -> {
                core.setPropertyString("keepaspect", "no")
                core.setPropertyString("panscan", "0.0")
            }
        }
    }

    /**
     * Sends a raw command directly to the MPV C-core.
     * Useful for advanced manipulation like custom shaders or subtitle tracks.
     */
    fun sendCommand(vararg args: String) {
        core.command(*args)
    }

    override fun play() = core.play()
    override fun pause() = core.pause()
    override fun stop() = core.stop()
    override fun replay() = core.replay()
    override fun seekTo(positionMs: Long) = core.seekTo(positionMs)
    override fun seekToItem(index: Int, positionMs: Long) {
        core.skipToIndex(index)
        if (positionMs > 0) core.seekTo(positionMs)
    }
    override fun setVolume(volume: Float) = core.setVolume(volume)
    override fun setVolumeMultiplier(multiplier: Float) = core.setVolumeMultiplier(multiplier)
    override fun setAudioFocusEnabled(enabled: Boolean) {} // Handled by VideoController
    
    override fun setRepeatMode(mode: RepeatMode) {
        core.setRepeatMode(mode)
    }

    override fun setPlaybackSpeed(speed: Float) {
        core.setPlaybackSpeed(speed)
    }

    override fun setPlaybackPitch(pitch: Float) {
        core.setPlaybackPitch(pitch)
    }

    override fun getCurrentPosition(): Long = core.state.value.positionMs
    override fun getDuration(): Long = core.state.value.durationMs
    override fun getCurrentMediaIndex(): Int = core.state.value.currentIndex

    override fun updatePlaylist(mediaList: List<PlayableMedia>, newCurrentIndex: Int) {
        PlaybackLogger.log(TAG, "MpvVideo: Updating playlist metadata (newIndex: $newCurrentIndex)")
        currentMediaList = mediaList
        
        val mediaItems = mediaList.map { media ->
            MediaItem.Builder()
                .setMediaId(media.uid)
                .setUri(media.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(media.title)
                        .setArtworkUri(media.artworkUri)
                        .setExtras(android.os.Bundle().apply {
                            media.extras.forEach { (key, value) -> putString(key, value) }
                        })
                        .build()
                )
                .build()
        }
        
        core.updatePlaylist(mediaItems, newCurrentIndex)
        bridgePlayer.invalidate()
    }

    override fun setVideoView(view: Any?) {
        currentSurfaceView?.holder?.removeCallback(this)
        currentSurfaceView = null
        if (view is SurfaceView) {
            currentSurfaceView = view
            view.holder.addCallback(this)
            
            // If the surface is already valid, attach it immediately
            if (view.holder.surface.isValid) {
                core.attachSurface(view.holder.surface)
            }
        } else {
            core.detachSurface()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { 
        core.attachSurface(holder.surface) 
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        core.notifySurfaceChanged(width, height)
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) { 
        core.detachSurface() 
    }

    override fun release() {
        PlaybackLogger.log(TAG, "MpvEngine: Detaching (Entering grace period)")
        currentSurfaceView?.holder?.removeCallback(this)
        core.pause()
        core.detachSurface()
        
        val grace = PlaybackManager.mpvGracePeriodMs
        if (grace < 0L) return // Never destroy
        
        cancelShutdownTimer()
        shutdownJob = scope.launch {
            delay(grace)
            PlaybackLogger.log(TAG, "MPV Grace Period Expired ($grace ms). Destroying native core.")
            core.destroyBlocking()
            synchronized(MpvEngine::class.java) {
                instance = null
            }
        }
    }
}
