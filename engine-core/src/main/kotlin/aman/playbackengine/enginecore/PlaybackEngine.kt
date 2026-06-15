package aman.playbackengine.enginecore

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface that every playback engine (ExoPlayer, MPV, etc.) must implement.
 */
interface PlaybackEngine {
    val type: EngineType
    val playbackState: StateFlow<EngineState>
    val playWhenReady: StateFlow<Boolean>
    fun getMedia3Player(): Player?
    
    /**
     * Initializes the engine with a full playlist.
     */
    fun prepare(mediaList: List<PlayableMedia>, startIndex: Int = 0, startPositionMs: Long = 0L, autoPlay: Boolean = false)
    
    /**
     * Re-prepares the current media items without resetting the playlist or surface.
     * Useful for resuming from a Stopped/Idle state without visual flashes.
     */
    fun prepare()
    
    /**
     * Pre-loads the next media item.
     */
    fun enqueueNext(media: PlayableMedia)

    /**
     * Surgically adds an item to the engine's internal playlist representation.
     */
    fun addQueueItem(index: Int, media: PlayableMedia)

    /**
     * Surgically removes an item from the engine's internal playlist representation.
     */
    fun removeQueueItem(index: Int)

    /**
     * Surgically moves an item within the engine's internal playlist representation.
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int)
    
    /**
     * Clears any pre-loaded upcoming media.
     */
    fun clearQueue()
    
    fun play()
    fun pause()
    fun stop()
    
    /**
     * Restarts the current media item from the beginning.
     */
    fun replay()
    
    fun seekTo(positionMs: Long)
    fun seekToItem(index: Int, positionMs: Long = 0L)
    fun setVolume(volume: Float)
    fun setVolumeMultiplier(multiplier: Float)
    
    /**
     * Callback triggered when the engine automatically transitions to the next media item.
     */
    var onMediaItemTransition: (() -> Unit)?
    var onSubtitlesChanged: ((List<SubtitleTrack>) -> Unit)?
    var onAudioTracksChanged: ((List<AudioTrack>) -> Unit)?
    
    /**
     * Enables or disables automatic audio focus handling by the engine.
     */
    fun setAudioFocusEnabled(enabled: Boolean)
    
    /**
     * Sets the repeat mode for the engine.
     * Engines handle RepeatMode.ONE natively.
     */
    fun setRepeatMode(mode: RepeatMode)

    /**
     * Sets the playback speed. (Default: 1.0)
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Sets the playback pitch. (Default: 1.0)
     */
    fun setPlaybackPitch(pitch: Float)

    /**
     * Enables or disables automatic transition to the next media item.
     */
    fun setAutoplay(enabled: Boolean)

    fun getCurrentPosition(): Long
    fun getDuration(): Long
    fun getCurrentMediaIndex(): Int
    
    /**
     * Seamlessly updates the engine's internal playlist representation without 
     * interrupting the currently playing item.
     * 
     * @param mediaList The new full playlist.
     * @param newCurrentIndex The index of the currently playing item within the new list.
     */
    fun updatePlaylist(mediaList: List<PlayableMedia>, newCurrentIndex: Int)
    
    fun release()
}

/**
 * Specialized engine for audio-only playback.
 */
interface AudioEngine : PlaybackEngine

/**
 * Specialized engine for video playback, supporting surfaces and hardware decoding.
 */
interface VideoEngine : PlaybackEngine {
    /**
     * Attaches or clears the video output surface.
     */
    fun setVideoView(view: Any?)

    /**
     * Sets the hardware decoding strategy.
     */
    fun setDecoderPolicy(policy: DecoderPolicy)
    
    // --- Subtitles ---
    fun selectSubtitleTrack(trackId: String?)
    
    // --- Audio Tracks ---
    fun selectAudioTrack(trackId: String)
    
    fun setScaleMode(mode: ScaleMode)
}
