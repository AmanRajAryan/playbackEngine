package aman.playbackengine.enginecore

import android.content.Context
import aman.playbackengine.enginecore.audio.AudioController
import aman.playbackengine.enginecore.video.VideoController

/**
 * Entry point for creating playback controllers.
 */
class PlaybackController {

    class Builder(private val context: Context) {
        private var defaultEngine: EngineType = EngineType.EXOPLAYER
        private var ignoreAudioFocusLoss: Boolean = false
        private var volume: Float = 1.0f

        fun setDefaultEngine(type: EngineType) = apply { this.defaultEngine = type }
        fun setIgnoreAudioFocusLoss(enabled: Boolean) = apply { this.ignoreAudioFocusLoss = enabled }
        fun setInitialVolume(volume: Float) = apply { this.volume = volume }

        fun buildAudio(): AudioController {
            return AudioController(context).apply {
                this.ignoreAudioFocusLoss = this@Builder.ignoreAudioFocusLoss
                this.setVolume(this@Builder.volume)
            }
        }

        fun buildVideo(): VideoController {
            return VideoController(context).apply {
                this.ignoreAudioFocusLoss = this@Builder.ignoreAudioFocusLoss
                this.setVolume(this@Builder.volume)
            }
        }
    }
}
