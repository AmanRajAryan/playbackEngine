package aman.playbackengine.enginecore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import aman.playbackengine.enginecore.internal.persistence.QueueSnapshot

/**
 * Handles the logic for managing a list of [PlayableMedia].
 * Responsible for unique ID generation, atomic list updates, and persistent shuffling.
 */
class MediaQueue {
    private val mutex = Mutex()
    
    // The "Master List" is the persistent, ordered sequence (e.g., Album order).
    private var masterList = mutableListOf<PlayableMedia>()
    
    // The "Shuffled List" is the persistent randomized sequence.
    private var shuffledList = mutableListOf<PlayableMedia>()
    
    private var isShuffleEnabled = false

    private val _items = MutableStateFlow<List<PlayableMedia>>(emptyList())
    
    /**
     * The active list being used for playback (either Master or Shuffled).
     */
    val items: StateFlow<List<PlayableMedia>> = _items.asStateFlow()

    /**
     * Replaces the entire queue with a new list.
     */
    suspend fun set(newList: List<PlayableMedia>, startIndex: Int = 0, playShuffled: Boolean = false): Int = mutex.withLock {
        PlaybackLogger.log("MediaQueue", "Queue SET called. Items: ${newList.size}, startIndex: $startIndex, playShuffled: $playShuffled")
        val uniqueList = newList.mapIndexed { i, media ->
            ensureUniqueUid(media, i)
        }
        
        masterList = uniqueList.toMutableList()
        shuffledList.clear() // Always clear old shuffle for a fresh playlist
        isShuffleEnabled = playShuffled // Force the new state
        
        if (isShuffleEnabled) {
            generateShuffledList(masterList.getOrNull(startIndex))
            _items.value = shuffledList.toList()
            return 0 // Current item is now at the top
        } else {
            _items.value = masterList.toList()
            return if (masterList.isNotEmpty()) {
                startIndex.coerceIn(masterList.indices)
            } else {
                0
            }
        }
    }

    /**
     * Toggles shuffle mode. Reuses the existing shuffled list if available.
     * @return The new index of the [currentUid] in the newly active list.
     */
    suspend fun toggleShuffle(currentUid: String?): Int = mutex.withLock {
        isShuffleEnabled = !isShuffleEnabled
        
        if (isShuffleEnabled) {
            // OFF -> ON
            if (shuffledList.isEmpty() && masterList.isNotEmpty()) {
                val currentItem = masterList.find { it.uid == currentUid } ?: masterList.firstOrNull()
                generateShuffledList(currentItem)
            }
            _items.value = shuffledList.toList()
            return shuffledList.indexOfFirst { it.uid == currentUid }.coerceAtLeast(0)
        } else {
            // ON -> OFF
            _items.value = masterList.toList()
            return masterList.indexOfFirst { it.uid == currentUid }.coerceAtLeast(0)
        }
    }

    /**
     * Forces a fresh randomization of the queue.
     */
    suspend fun reshuffle(currentUid: String?) = mutex.withLock {
        isShuffleEnabled = true
        val currentItem = masterList.find { it.uid == currentUid }
        generateShuffledList(currentItem)
        _items.value = shuffledList.toList()
    }

    private fun generateShuffledList(hoistItem: PlayableMedia?) {
        val newList = masterList.toMutableList()
        if (hoistItem != null) {
            newList.removeIf { it.uid == hoistItem.uid }
            newList.shuffle()
            shuffledList = (mutableListOf(hoistItem) + newList).toMutableList()
        } else {
            newList.shuffle()
            shuffledList = newList
        }
    }

    /**
     * Adds an item to the end of BOTH lists.
     */
    suspend fun enqueue(media: PlayableMedia): PlayableMedia = mutex.withLock {
        val uniqueMedia = ensureUniqueUid(media)
        masterList.add(uniqueMedia)
        if (shuffledList.isNotEmpty()) {
            shuffledList.add(uniqueMedia)
        }
        
        refreshActiveList()
        return uniqueMedia
    }

    /**
     * Inserts an item immediately after the current item in BOTH lists.
     */
    suspend fun insertNext(currentUid: String?, media: PlayableMedia): PlayableMedia = mutex.withLock {
        val uniqueMedia = ensureUniqueUid(media)
        
        // 1. Sync Master
        val masterIdx = masterList.indexOfFirst { it.uid == currentUid }
        if (masterIdx != -1) {
            masterList.add(masterIdx + 1, uniqueMedia)
        } else {
            masterList.add(uniqueMedia)
        }
        
        // 2. Sync Shuffled
        if (shuffledList.isNotEmpty()) {
            val shuffledIdx = shuffledList.indexOfFirst { it.uid == currentUid }
            if (shuffledIdx != -1) {
                shuffledList.add(shuffledIdx + 1, uniqueMedia)
            } else {
                shuffledList.add(uniqueMedia)
            }
        }
        
        refreshActiveList()
        return uniqueMedia
    }

    /**
     * Removes an item by its UID from BOTH lists.
     */
    suspend fun removeByUid(uid: String): PlayableMedia? = mutex.withLock {
        val removedFromMaster = masterList.find { it.uid == uid }
        if (removedFromMaster != null) {
            masterList.removeIf { it.uid == uid }
            shuffledList.removeIf { it.uid == uid }
            refreshActiveList()
        }
        return removedFromMaster
    }

    /**
     * Positional removal from the active list.
     */
    suspend fun remove(index: Int): PlayableMedia? = mutex.withLock {
        val active = if (isShuffleEnabled) shuffledList else masterList
        val item = active.getOrNull(index) ?: return@withLock null
        
        // Inline the removal logic to maintain the lock
        masterList.removeIf { it.uid == item.uid }
        shuffledList.removeIf { it.uid == item.uid }
        refreshActiveList()
        
        return@withLock item
    }

    /**
     * Moves an item within the ACTIVE list. (No bleeding per requirement).
     */
    suspend fun move(fromIndex: Int, toIndex: Int) {
        mutex.withLock {
            val active = if (isShuffleEnabled) shuffledList else masterList
            if (fromIndex !in active.indices || toIndex !in active.indices) return@withLock
            
            val item = active.removeAt(fromIndex)
            active.add(toIndex, item)
            
            _items.value = active.toList()
        }
    }

    /**
     * Updates an existing item in BOTH lists without changing its UID or position.
     */
    suspend fun updateItemByUid(uid: String, updateFn: (PlayableMedia) -> PlayableMedia) = mutex.withLock {
        val masterIdx = masterList.indexOfFirst { it.uid == uid }
        if (masterIdx != -1) {
            masterList[masterIdx] = updateFn(masterList[masterIdx])
        }
        val shuffledIdx = shuffledList.indexOfFirst { it.uid == uid }
        if (shuffledIdx != -1) {
            shuffledList[shuffledIdx] = updateFn(shuffledList[shuffledIdx])
        }
        if (masterIdx != -1 || shuffledIdx != -1) {
            refreshActiveList()
        }
    }

    private fun refreshActiveList() {
        _items.value = (if (isShuffleEnabled) shuffledList else masterList).toList()
    }

    suspend fun clear() {
        mutex.withLock {
            masterList.clear()
            shuffledList.clear()
            _items.value = emptyList()
        }
    }

    private fun ensureUniqueUid(media: PlayableMedia, suffix: Int? = null): PlayableMedia {
        val timestamp = System.nanoTime()
        val extra = if (suffix != null) "_$suffix" else ""
        return media.copy(uid = "${media.id}_${timestamp}$extra")
    }

    fun get(index: Int): PlayableMedia? = _items.value.getOrNull(index)
    fun indexOf(media: PlayableMedia): Int = _items.value.indexOfFirst { it.uid == media.uid }
    fun size(): Int = _items.value.size
    fun isEmpty(): Boolean = _items.value.isEmpty()
    fun isShuffleOn(): Boolean = isShuffleEnabled

    /**
     * Captures the current internal state of the queue for persistence.
     */
    suspend fun getSnapshot(currentUid: String?, positionMs: Long = 0L): QueueSnapshot = mutex.withLock {
        QueueSnapshot(
            masterList = masterList.toList(),
            shuffledList = shuffledList.toList(),
            currentUid = currentUid,
            positionMs = positionMs
        )
    }

    /**
     * Restores the internal state from a previously saved snapshot.
     */
    suspend fun restoreFromSnapshot(snapshot: QueueSnapshot) = mutex.withLock {
        masterList = snapshot.masterList.toMutableList()
        shuffledList = snapshot.shuffledList.toMutableList()
        refreshActiveList()
    }

    /**
     * Synchronizes the internal shuffle mode with an external truth without triggering playlist updates.
     */
    fun syncShuffleMode(enabled: Boolean) {
        isShuffleEnabled = enabled
    }
}
