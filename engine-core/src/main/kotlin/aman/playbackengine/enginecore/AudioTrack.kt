package aman.playbackengine.enginecore

/**
 * A unified model representing a playable audio track across different engines.
 */
data class AudioTrack(
    val id: String,          // Unique ID used by the specific engine
    val language: String?,   // ISO language code (e.g., "en", "fra")
    val label: String?,      // Human-readable title (e.g., "Stereo", "5.1 Surround")
    val isSelected: Boolean  // True if this is the currently active audio track
)
