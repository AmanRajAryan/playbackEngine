package aman.playbackengine.enginecore

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * A universal contract for controlling a media playback stream.
 * 
 * This interface allows external components (like PlaybackService or UI) to interact
 * with any stream (Audio or Video) without needing to know the underlying implementation details.
 */
interface PlaybackAuthority {
    /** The unified source of truth for the current playback session. */
    val state: StateFlow<PlaybackState>

    /** Exposes the active Media3 Player instance for session attachment. */
    val currentMedia3Player: StateFlow<Player?>

    /**
     * If true, focus loss events from the system will be ignored.
     */
    var ignoreAudioFocusLoss: Boolean
    
    // --- Transport Commands ---
    fun play()
    fun pause()
    fun stop()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    
    fun setVolume(volume: Float)
    fun getManualVolume(): Float
    fun setPlaybackSpeed(speed: Float)
    fun setPlaybackPitch(pitch: Float)
    
    // --- Queue Commands ---
    fun toggleShuffle()
    fun reshuffle()
    fun toggleRepeatMode()
    fun skipToIndex(index: Int)
    fun toggleAlternativeEngine(useAlternative: Boolean)
    fun playNext(media: PlayableMedia)
    fun enqueue(media: PlayableMedia)
    fun remove(index: Int)
    fun move(fromIndex: Int, toIndex: Int)
    
    // --- Timer Commands ---
    fun startSleepTimer(durationMs: Long)
    fun startSleepTimerEndOfTrack()
    fun cancelSleepTimer()

    // --- Maintenance ---
    fun refreshReplayGain()
    fun getMedia3Player(): Player?
    
    fun selectSubtitleTrack(trackId: String?)
    fun setExternalSubtitle(uri: String)
    fun selectAudioTrack(trackId: String)
    fun setScaleMode(mode: ScaleMode)
    fun release()
    fun handleMpvRevocation()
}
