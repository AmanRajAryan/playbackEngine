package aman.playbackengine.enginecore

enum class ScaleMode {
    FIT,       // Keep aspect ratio, letterbox if needed
    FILL,      // Keep aspect ratio, crop outside edges to fill screen
    STRETCH    // Ignore aspect ratio, stretch to screen
}
