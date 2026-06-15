package aman.playbackengine.engineexoplayer.audio

import android.media.audiofx.Equalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import aman.playbackengine.enginecore.equalizer.EqualizerManager
import aman.playbackengine.enginecore.PlaybackLogger

class ExoEqualizerBinder {
    private var activeEqualizer: Equalizer? = null
    private var eqJob: Job? = null
    private var eqBandJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun bindSession(audioSessionId: Int) {
        release()
        try {
            activeEqualizer = Equalizer(0, audioSessionId)
            
            eqJob = scope.launch {
                EqualizerManager.enabled.collectLatest { enabled ->
                    try {
                        activeEqualizer?.enabled = enabled
                    } catch (e: Exception) {
                        PlaybackLogger.log("ExoEqualizerBinder", "Failed to set EQ enabled: ${e.message}")
                    }
                }
            }

            eqBandJob = scope.launch {
                EqualizerManager.bands.collectLatest { bands ->
                    try {
                        bands.forEach { band ->
                            activeEqualizer?.setBandLevel(band.id, band.currentLevel)
                        }
                    } catch (e: Exception) {
                        PlaybackLogger.log("ExoEqualizerBinder", "Failed to set EQ band: ${e.message}")
                    }
                }
            }
            PlaybackLogger.log("ExoEqualizerBinder", "Bound EQ to session $audioSessionId")
        } catch (e: Exception) {
            PlaybackLogger.log("ExoEqualizerBinder", "Failed to bind hardware Equalizer to session $audioSessionId: ${e.message}")
            activeEqualizer = null
        }
    }

    fun release() {
        eqJob?.cancel()
        eqBandJob?.cancel()
        eqJob = null
        eqBandJob = null
        try {
            activeEqualizer?.release()
        } catch (e: Exception) {
            // ignore
        }
        activeEqualizer = null
    }
}
