package aman.playbackengine.enginecore.audio

import android.content.Context
import androidx.media3.common.Player
import aman.playbackengine.enginecore.*

/**
 * Audio stream controller.
 * Orchestrates Audio Focus and Engine Lifecycle.
 */
class AudioController(context: Context) : BasePlaybackController<AudioEngine>(context) {
    override val isVideoController: Boolean = false

    override fun createEngine(isAlternative: Boolean): AudioEngine {
        val type = if (isAlternative) PlaybackManager.getAlternativeType() else PlaybackManager.defaultEngineType
        return PlaybackManager.createAudioEngine(context, type)
    }
}
