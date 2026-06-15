package aman.playbackengine.enginecore.internal.controller

import android.content.Context
import aman.playbackengine.enginecore.LibraryAudioFocusManager
import aman.playbackengine.enginecore.PlaybackLogger

/**
 * Commands sent from the FocusDelegate to the Controller.
 */
internal sealed class FocusCommand {
    object Pause : FocusCommand()
    object Resume : FocusCommand()
    data class Duck(val multiplier: Float) : FocusCommand()
}

/**
 * Delegate responsible for managing system audio focus and interruption policies.
 * Decouples system-level focus events from media playback orchestration.
 */
internal class FocusDelegate(
    private val context: Context,
    private val onCommand: (FocusCommand) -> Unit
) : LibraryAudioFocusManager.Callback {

    private val TAG = "FocusDelegate"
    private var focusManager: LibraryAudioFocusManager? = null

    /**
     * If true, focus loss events from the system (e.g., other apps starting playback)
     * will be ignored, and this player will continue to play alongside other audio.
     */
    var ignoreAudioFocusLoss: Boolean = false
        set(value) {
            field = value
            focusManager?.ignoreAudioFocusLoss = value
        }

    /**
     * Initializes the underlying focus manager.
     */
    fun ensureSetup() {
        if (focusManager == null) {
            focusManager = LibraryAudioFocusManager(context, this)
        }
    }

    /**
     * Requests audio focus from the Android system.
     * @return true if focus was successfully granted.
     */
    fun requestFocus(): Boolean {
        ensureSetup()
        return focusManager?.requestFocus() ?: false
    }

    fun abandonFocus() {
        focusManager?.abandonFocus()
    }

    /**
     * Informs the focus manager of changes in local playback state.
     */
    fun onPlayerStateChanged(isPlaying: Boolean) {
        focusManager?.onPlayerStateChanged(isPlaying)
    }

    // --- AudioFocusManager.Callback Implementation ---

    override fun onFocusRequestPause() {
        PlaybackLogger.log(TAG, "Audio Focus: Interruption detected. Pausing.")
        onCommand(FocusCommand.Pause)
    }

    override fun onFocusRequestResume() {
        PlaybackLogger.log(TAG, "Audio Focus: Interruption cleared. Resuming.")
        onCommand(FocusCommand.Resume)
    }

    override fun onFocusRequestDucking(duck: Boolean) {
        val multiplier = if (duck) 0.2f else 1.0f
        PlaybackLogger.log(TAG, "Audio Focus: Ducking changed to $duck (Volume: ${multiplier * 100}%)")
        onCommand(FocusCommand.Duck(multiplier))
    }
}
