package aman.playbackengine.enginecore.internal.persistence

import aman.playbackengine.enginecore.PlayableMedia
import aman.playbackengine.enginecore.RepeatMode

/**
 * Snapshot of the current queue state for persistent storage.
 */
data class QueueSnapshot(
    val masterList: List<PlayableMedia>,
    val shuffledList: List<PlayableMedia>,
    val isShuffleEnabled: Boolean,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f,
    val currentUid: String?,
    val positionMs: Long = 0L
)
