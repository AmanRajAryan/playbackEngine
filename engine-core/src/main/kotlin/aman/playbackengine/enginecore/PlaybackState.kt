package aman.playbackengine.enginecore

/**
 * Unified state of a [PlaybackController].
 */
data class PlaybackState(
    val engineState: EngineState = EngineState.Uninitialized,
    val currentMedia: PlayableMedia? = null,
    val isPlaying: Boolean = false,
    val isMpvActive: Boolean = false,
    val queue: List<PlayableMedia> = emptyList(),
    val currentVolume: Float = 1.0f,
    val videoDecoderPolicy: DecoderPolicy = DecoderPolicy.HW_PLUS,
    val isShuffleModeEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f,
    val sleepTimerState: SleepTimerState = SleepTimerState.Inactive,
    val replayGainMultiplier: Float = 1.0f,
    val sleepFadeMultiplier: Float = 1.0f
)
