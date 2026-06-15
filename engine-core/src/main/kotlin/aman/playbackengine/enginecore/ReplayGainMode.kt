package aman.playbackengine.enginecore

/**
 * Modes for loudness normalization.
 */
enum class ReplayGainMode {
    /**
     * No normalization.
     */
    OFF,

    /**
     * Level each track to the target loudness.
     */
    TRACK,

    /**
     * Level the whole album, preserving relative volume between tracks.
     */
    ALBUM
}
