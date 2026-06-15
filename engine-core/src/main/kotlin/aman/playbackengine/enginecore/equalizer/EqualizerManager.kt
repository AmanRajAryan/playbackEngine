package aman.playbackengine.enginecore.equalizer

import android.media.audiofx.Equalizer
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import aman.playbackengine.enginecore.PlaybackLogger

object EqualizerManager : PlaybackEqualizer {
    private const val PREFS_NAME = "playback_engine_equalizer"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_NUM_BANDS = "eq_num_bands"
    
    private var prefs: SharedPreferences? = null
    private val _enabled = MutableStateFlow(false)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _bands = MutableStateFlow<List<EqBand>>(emptyList())
    override val bands: StateFlow<List<EqBand>> = _bands.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return // Already initialized
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        val p = prefs ?: return
        val numBands = p.getInt(KEY_NUM_BANDS, 0)
        
        if (numBands > 0) {
            val loadedBands = mutableListOf<EqBand>()
            for (i in 0 until numBands) {
                loadedBands.add(
                    EqBand(
                        id = i.toShort(),
                        centerFreqHz = p.getInt("eq_band_${i}_freq", 0),
                        minLevel = p.getInt("eq_band_${i}_min", -1500).toShort(),
                        maxLevel = p.getInt("eq_band_${i}_max", 1500).toShort(),
                        currentLevel = p.getInt("eq_band_${i}_level", 0).toShort()
                    )
                )
            }
            _bands.value = loadedBands
            _enabled.value = p.getBoolean(KEY_ENABLED, false)
            PlaybackLogger.log("EqualizerManager", "Loaded $numBands bands from SharedPreferences")
        } else {
            probeHardware()
        }
    }

    private fun saveBandsToPrefs(bandsToSave: List<EqBand>) {
        val editor = prefs?.edit() ?: return
        editor.putInt(KEY_NUM_BANDS, bandsToSave.size)
        for (i in bandsToSave.indices) {
            val band = bandsToSave[i]
            editor.putInt("eq_band_${i}_freq", band.centerFreqHz)
            editor.putInt("eq_band_${i}_min", band.minLevel.toInt())
            editor.putInt("eq_band_${i}_max", band.maxLevel.toInt())
            editor.putInt("eq_band_${i}_level", band.currentLevel.toInt())
        }
        editor.apply()
    }

    private fun probeHardware() {
        var dummyPlayer: android.media.MediaPlayer? = null
        var dummyEq: Equalizer? = null
        try {
            // Spin up a silent MediaPlayer to get a valid, app-owned audio session ID.
            // Probing session 0 directly is blocked on modern Android versions.
            dummyPlayer = android.media.MediaPlayer()
            val sessionId = dummyPlayer.audioSessionId
            
            dummyEq = Equalizer(0, sessionId)
            val numBands = dummyEq.numberOfBands
            val levelRange = dummyEq.bandLevelRange
            val minLevel = levelRange[0]
            val maxLevel = levelRange[1]
            
            val initialBands = mutableListOf<EqBand>()
            for (i in 0 until numBands) {
                val bandId = i.toShort()
                val freq = dummyEq.getCenterFreq(bandId) // returns mHz
                initialBands.add(
                    EqBand(
                        id = bandId,
                        centerFreqHz = freq,
                        minLevel = minLevel,
                        maxLevel = maxLevel,
                        currentLevel = 0
                    )
                )
            }
            _bands.value = initialBands
            PlaybackLogger.log("EqualizerManager", "Hardware probe success: ${numBands} bands available.")
        } catch (e: Exception) {
            PlaybackLogger.log("EqualizerManager", "Hardware probe failed: ${e.message}. Falling back to standard 5-band.")
            // Safe fallback if hardware denies probe
            _bands.value = listOf(
                EqBand(0, 60000, -1500, 1500, 0),
                EqBand(1, 230000, -1500, 1500, 0),
                EqBand(2, 910000, -1500, 1500, 0),
                EqBand(3, 3600000, -1500, 1500, 0),
                EqBand(4, 14000000, -1500, 1500, 0)
            )
        } finally {
            saveBandsToPrefs(_bands.value)
            try {
                dummyEq?.release()
                dummyPlayer?.release()
            } catch (e: Exception) {
                // Ignore release errors
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    override fun setBandLevel(bandId: Short, level: Short) {
        _bands.update { currentList ->
            currentList.map { 
                if (it.id == bandId) it.copy(currentLevel = level) else it 
            }
        }
        prefs?.edit()?.putInt("eq_band_${bandId}_level", level.toInt())?.apply()
    }
}
