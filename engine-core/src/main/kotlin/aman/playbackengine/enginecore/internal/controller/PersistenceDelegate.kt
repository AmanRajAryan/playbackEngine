package aman.playbackengine.enginecore.internal.controller

import aman.playbackengine.enginecore.PlayableMedia
import aman.playbackengine.enginecore.PlaybackLogger
import aman.playbackengine.enginecore.PlaybackState
import aman.playbackengine.enginecore.MediaQueue
import aman.playbackengine.enginecore.RepeatMode
import aman.playbackengine.enginecore.internal.persistence.QueuePersister
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Depth of persistence for a [PersistenceDelegate.triggerSave] call.
 */
internal enum class SaveLevel {
    /** Tier 1: Just the position (O(1)). */
    POSITION,
    
    /** Tier 2: UID, Repeat, Speed, Pitch (O(1)). No queue rewrite. */
    METADATA,
    
    /** Tier 3: Full structural sync (O(N)). Re-writes the entire playlist. */
    FULL
}

/**
 * Delegate responsible for managing the tiered database persistence strategy.
 * Implements debouncing and periodic updates to optimize storage I/O performance.
 */
internal class PersistenceDelegate(
    private val scope: CoroutineScope,
    private val queue: MediaQueue,
    private val persister: QueuePersister,
    private val stateProvider: () -> PlaybackState,
    private val logTag: String
) {
    private var debounceSaveJob: Job? = null
    private var periodicUpdateJob: Job? = null

    init {
        startPeriodicUpdates()
    }

    private fun startPeriodicUpdates() {
        periodicUpdateJob?.cancel()
        periodicUpdateJob = scope.launch {
            while (isActive) {
                delay(10000) // 10s status update
                if (stateProvider().isPlaying) {
                    triggerSave(SaveLevel.POSITION)
                }
            }
        }
    }

    /**
     * Orchestrates the persistence depth based on the requested [SaveLevel].
     * @param enginePosition If provided, used as the current position. Otherwise falls back to state.
     */
    fun triggerSave(level: SaveLevel = SaveLevel.FULL, enginePosition: Long? = null) {
        val state = stateProvider()
        val currentUid = state.currentMedia?.uid
        val pos = enginePosition ?: state.currentPositionMs
        
        when (level) {
            SaveLevel.POSITION -> {
                // Debounce position updates during active seeking or scrubbing.
                // Ensures a write operation only occurs after 500ms of inactivity.
                debounceSaveJob?.cancel()
                debounceSaveJob = scope.launch(Dispatchers.IO) {
                    delay(500)
                    persister.updatePosition(pos)
                    PlaybackLogger.log(logTag, "Persistence: Position updated to ${pos}ms")
                }
            }
            SaveLevel.METADATA -> {
                PlaybackLogger.log(logTag, "Persistence: Metadata saved (O(1))")
                scope.launch(Dispatchers.IO) {
                    val snapshot = queue.getSnapshot(currentUid, pos)
                    persister.saveMetadata(snapshot)
                }
            }
            SaveLevel.FULL -> {
                // Debounce structural changes (reordering/add/remove) to prevent 
                // redundant full-queue serialization during rapid modifications.
                debounceSaveJob?.cancel()
                debounceSaveJob = scope.launch(Dispatchers.IO) {
                    delay(1000) // 1s cooldown for full-queue sync
                    
                    // Read the latest state after the debounce delay
                    val latestState = stateProvider()
                    val snapshot = queue.getSnapshot(latestState.currentMedia?.uid, latestState.currentPositionMs)
                    
                    persister.saveFullQueue(snapshot)
                    PlaybackLogger.log(logTag, "Persistence: Full queue sync completed after debounce")
                }
            }
        }
    }

    fun release() {
        periodicUpdateJob?.cancel()
        debounceSaveJob?.cancel()
        // Final save is handled by the controller calling triggerSave one last time
    }
}
