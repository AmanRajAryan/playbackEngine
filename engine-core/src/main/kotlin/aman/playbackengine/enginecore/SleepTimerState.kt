package aman.playbackengine.enginecore

/**
 * Represents the state of the sleep timer.
 */
sealed class SleepTimerState {
    /** Sleep timer is not active. */
    object Inactive : SleepTimerState()
    
    /** Sleep timer will stop playback after a specific duration. */
    data class TimeBased(val remainingMs: Long) : SleepTimerState()
    
    /** Sleep timer will stop playback after the current song finishes. */
    object EndOfTrack : SleepTimerState()
}
