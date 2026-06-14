package aman.playbackengine.enginecore.video

import android.content.Context
import androidx.media3.common.Player
import aman.playbackengine.enginecore.*

/**
 * Video stream controller.
 * Orchestrates Audio Focus and Engine Lifecycle for Video.
 */
class VideoController(context: Context) : BasePlaybackController<VideoEngine>(context) {
    override val isVideoController: Boolean = true

    override fun createEngine(isAlternative: Boolean): VideoEngine {
        val type = if (isAlternative) PlaybackManager.getAlternativeType() else PlaybackManager.defaultEngineType
        return PlaybackManager.createVideoEngine(context, type)
    }

    private var autoplay: Boolean = true
    private var currentDecoderPolicy: DecoderPolicy = DecoderPolicy.HW_PLUS

    fun setAutoplay(enabled: Boolean) {
        autoplay = enabled
        currentEngine?.setAutoplay(enabled)
    }

    fun setDecoderPolicy(policy: DecoderPolicy) {
        currentDecoderPolicy = policy
        updateState { it.copy(videoDecoderPolicy = policy) }
        currentEngine?.setDecoderPolicy(policy)
    }

    override fun onEngineConfigured(engine: VideoEngine) {
        engine.setAutoplay(autoplay)
        engine.setDecoderPolicy(currentDecoderPolicy)
        updateState { it.copy(videoDecoderPolicy = currentDecoderPolicy) }
    }

    fun setVideoView(view: Any?) { 
        ensureEngine().setVideoView(view)
    }
}
