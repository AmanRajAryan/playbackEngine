package aman.playbackengine.enginecore.internal.persistence

import android.content.Context
import aman.playbackengine.enginecore.PlaybackLogger

/**
 * Handles saving and loading the [QueueSnapshot] using a native SQLite backend.
 */
internal class QueuePersister(
    private val context: Context,
    private val filename: String
) {
    private val TAG = "QueuePersister"
    private val dbHelper = PlaybackDatabaseHelper(context)
    private val type = if (filename.contains("audio")) "audio" else "video"

    /** Tier 1: Just the position. */
    fun updatePosition(positionMs: Long) {
        dbHelper.updatePosition(type, positionMs)
    }

    /** Tier 2: UID, Repeat, Speed, Pitch. (No Queue Rewrite) */
    fun saveMetadata(snapshot: QueueSnapshot) {
        dbHelper.saveMetadata(type, snapshot)
    }

    /** Tier 3: Full structural sync (Slow). */
    fun saveFullQueue(snapshot: QueueSnapshot) {
        dbHelper.saveFullQueue(type, snapshot)
    }

    fun load(): QueueSnapshot? {
        return dbHelper.loadSnapshot(type)
    }
    
    fun clear() {
        // Implementation for clearing specific stream items if needed
    }
}
