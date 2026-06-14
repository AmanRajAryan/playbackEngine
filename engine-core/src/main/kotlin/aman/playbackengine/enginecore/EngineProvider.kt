package aman.playbackengine.enginecore

import android.content.Context

/**
 * Interface that defines the contract for an engine module.
 * This allows the core library to remain implementation-agnostic.
 */
interface EngineProvider {
    /**
     * The type of engine this provider builds (ExoPlayer, MPV, etc.).
     */
    val type: EngineType

    /**
     * Creates an [AudioEngine] instance.
     */
    fun createAudioEngine(context: Context): AudioEngine

    /**
     * Creates a [VideoEngine] instance.
     */
    fun createVideoEngine(context: Context): VideoEngine

    /**
     * Called when the manager is releasing all resources.
     * Use this for native core destruction or static singleton cleanup.
     */
    fun onRelease()
}
