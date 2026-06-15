package aman.playbackengine.enginecore

/**
 * Represents the various states of a playback engine.
 */
sealed class EngineState {
    object Uninitialized : EngineState()
    object Idle : EngineState()
    object Buffering : EngineState()
    object Ready : EngineState()
    object Playing : EngineState()
    object Paused : EngineState()
    object Ended : EngineState()
    data class Error(val message: String, val throwable: Throwable? = null) : EngineState()
}
