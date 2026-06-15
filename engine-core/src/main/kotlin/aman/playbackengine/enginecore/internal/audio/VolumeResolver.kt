package aman.playbackengine.enginecore.internal.audio

import aman.playbackengine.enginecore.PlayableMedia
import aman.playbackengine.enginecore.PlaybackManager
import aman.playbackengine.enginecore.ReplayGainMode
import aman.playbackengine.enginecore.PlaybackLogger

/**
 * Pure logic class for calculating volume multipliers based on ReplayGain and Pre-Amp settings.
 */
internal object VolumeResolver {
    private const val TAG = "VolumeResolver"

    /**
     * Calculates the linear ReplayGain multiplier based on media metadata and global settings.
     * Includes Peak protection to prevent clipping.
     */
    fun resolveReplayGain(media: PlayableMedia): Float {
        val mode = PlaybackManager.replayGainMode
        val preAmp = PlaybackManager.preAmpGainDb
        
        // 1. Determine which gain value to use
        val mediaDb = if (mode == ReplayGainMode.OFF) 0.0 else {
            if (mode == ReplayGainMode.TRACK) media.trackGain else (media.albumGain ?: media.trackGain)
        } ?: 0.0
        
        val totalDb = mediaDb + preAmp
        
        // 2. Convert dB to linear multiplier: linear = 10 ^ (dB / 20)
        var multiplier = if (totalDb == 0.0) 1.0f else Math.pow(10.0, totalDb / 20.0).toFloat()
        
        // 3. Apply Peak protection (Safety)
        var finalRg = multiplier
        media.peak?.let {
            if (it > 0) {
                val maxSafe = (1.0 / it).toFloat()
                finalRg = finalRg.coerceAtMost(maxSafe)
            }
        }
        
        PlaybackLogger.log(TAG, "ReplayGain: Calculated multiplier for ${media.title} (RG:$mediaDb, PreAmp:$preAmp): $finalRg")
        return finalRg
    }
}
