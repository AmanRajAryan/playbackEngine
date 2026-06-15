package aman.playbackengine.enginecore.internal.persistence

import aman.playbackengine.enginecore.PlayableMedia
import aman.playbackengine.enginecore.RepeatMode

/**
 * Snapshot of the current queue state for persistent storage.
 */
data class QueueSnapshot(
    val masterList: List<PlayableMedia>,
    val shuffledList: List<PlayableMedia>,
    val currentUid: String?,
    val positionMs: Long = 0L
)
