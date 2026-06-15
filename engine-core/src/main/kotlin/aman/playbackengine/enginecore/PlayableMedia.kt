package aman.playbackengine.enginecore

import android.net.Uri

/**
 * Universal media model for the playback library.
 */
data class PlayableMedia(
    val id: String,
    val uid: String = id, // Unique Instance ID (Auto-generated on queue entry)
    val uri: Uri,
    val title: String,
    val subtitle: String? = null,
    val artworkUri: Uri? = null,
    val localPath: String? = null, // Path for high-fidelity tag/image extraction
    val durationMs: Long = 0,
    val isVideo: Boolean = false,
    val extras: Map<String, String> = emptyMap(), // Arbitrary persistent metadata attached to the media item
    val trackGain: Double? = null,
    val albumGain: Double? = null,
    val peak: Double? = null,
    val externalSubtitleUri: String? = null
)
