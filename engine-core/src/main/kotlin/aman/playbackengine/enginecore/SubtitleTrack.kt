package aman.playbackengine.enginecore

/**
 * A unified model representing a playable subtitle track across different engines.
 */
data class SubtitleTrack(
    val id: String,          // Unique ID used by the specific engine to select this track
    val language: String?,   // ISO language code (e.g., "en", "fra")
    val label: String?,      // Human-readable title (e.g., "English (Forced)")
    val isSelected: Boolean  // True if this is the currently active subtitle track
)
