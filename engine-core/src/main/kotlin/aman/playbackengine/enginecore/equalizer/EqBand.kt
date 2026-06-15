package aman.playbackengine.enginecore.equalizer

data class EqBand(
    val id: Short,
    val centerFreqHz: Int, // Note: stored in milliHertz (mHz) to match Android API
    val minLevel: Short,   // Note: stored in millibels (mB)
    val maxLevel: Short,
    var currentLevel: Short
)
