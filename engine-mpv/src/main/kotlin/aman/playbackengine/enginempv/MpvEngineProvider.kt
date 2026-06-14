package aman.playbackengine.enginempv

import android.content.Context
import aman.playbackengine.enginecore.AudioEngine
import aman.playbackengine.enginecore.EngineProvider
import aman.playbackengine.enginecore.EngineType
import aman.playbackengine.enginecore.VideoEngine
import aman.playbackengine.enginempv.MpvEngine

/**
 * Provider implementation for the libmpv engine module.
 */
class MpvEngineProvider : EngineProvider {
    override val type: EngineType = EngineType.MPV

    override fun createAudioEngine(context: Context): AudioEngine {
        return MpvEngine.getInstance(context)
    }

    override fun createVideoEngine(context: Context): VideoEngine {
        return MpvEngine.getInstance(context)
    }

    override fun onRelease() {
        MpvEngine.destroyInstance()
    }
}
