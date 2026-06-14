package aman.playbackengine.enginecore.internal.controller

import aman.playbackengine.enginecore.SleepTimerState
import kotlinx.coroutines.*

/**
 * Manages the countdown and volume fade-out for the Sleep Timer.
 * Isolated from the main controller to reduce complexity.
 */
internal class SleepTimerManager(
    private val scope: CoroutineScope,
    private val onStateChanged: (SleepTimerState, Float) -> Unit,
    private val onTimerFinished: () -> Unit
) {
    private var timerJob: Job? = null
    var fadeMultiplier: Float = 1.0f
        private set

    /**
     * Starts a time-based countdown.
     */
    fun start(durationMs: Long, isPlayingProvider: () -> Boolean) {
        cancel()
        timerJob = scope.launch {
            var remaining = durationMs
            notifyChange(SleepTimerState.TimeBased(remaining))

            while (remaining > 0) {
                delay(1000)
                if (isPlayingProvider()) {
                    remaining -= 1000
                    val safeRemaining = remaining.coerceAtLeast(0)
                    
                    // Fade logic (last 60s)
                    fadeMultiplier = if (safeRemaining <= 60_000L) {
                        safeRemaining / 60_000f
                    } else {
                        1.0f
                    }
                    
                    notifyChange(SleepTimerState.TimeBased(safeRemaining))
                }
            }
            
            // Final Execution
            fadeMultiplier = 0.0f
            notifyChange(SleepTimerState.TimeBased(0))
            
            // Give a tiny moment for the volume floor to hit the engine
            delay(100) 
            onTimerFinished()
            resetAndClean()
        }
    }

    /**
     * Sets the state to EndOfTrack.
     * The actual transition check remains in the Controller.
     */
    fun startEndOfTrack() {
        cancel()
        notifyChange(SleepTimerState.EndOfTrack)
    }

    /**
     * Cancels any active timer and restores volume.
     */
    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        resetAndClean()
    }

    private fun resetAndClean() {
        fadeMultiplier = 1.0f
        notifyChange(SleepTimerState.Inactive)
    }

    private fun notifyChange(state: SleepTimerState) {
        onStateChanged(state, fadeMultiplier)
    }
}
