package aman.playbackengine.enginecore.equalizer

import kotlinx.coroutines.flow.StateFlow

interface PlaybackEqualizer {
    val enabled: StateFlow<Boolean>
    val bands: StateFlow<List<EqBand>>
    
    fun setEnabled(enabled: Boolean)
    fun setBandLevel(bandId: Short, level: Short)
}
