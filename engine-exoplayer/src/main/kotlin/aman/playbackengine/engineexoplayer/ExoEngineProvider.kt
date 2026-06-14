package aman.playbackengine.engineexoplayer

import android.content.Context
import aman.playbackengine.enginecore.AudioEngine
import aman.playbackengine.enginecore.EngineProvider
import aman.playbackengine.enginecore.EngineType
import aman.playbackengine.enginecore.VideoEngine
import aman.playbackengine.engineexoplayer.audio.CrossfadeExoEngine
import aman.playbackengine.engineexoplayer.video.ExoVideoEngine

/**
 * Provider implementation for the ExoPlayer engine module.
 */
class ExoEngineProvider : EngineProvider {
    override val type: EngineType = EngineType.EXOPLAYER

    override fun createAudioEngine(context: Context): AudioEngine {
        return CrossfadeExoEngine(context)
    }

    override fun createVideoEngine(context: Context): VideoEngine {
        return ExoVideoEngine(context)
    }

    override fun onRelease() {
        // No static cleanup needed for ExoPlayer
    }
}
