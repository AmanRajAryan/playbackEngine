package aman.playbackengine.enginecore.internal.controller

import androidx.media3.common.Player
import aman.playbackengine.enginecore.*
import aman.playbackengine.enginecore.internal.persistence.QueueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/**
 * Single source of truth for the controller's reactive state.
 * Manages UI state, session restoration (persistence handoff), and atomic updates.
 */
internal class StateCoordinator(
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(PlaybackState(
        repeatMode = aman.playbackengine.enginecore.internal.persistence.PlaybackSettingsManager.getRepeatMode(),
        isShuffleModeEnabled = aman.playbackengine.enginecore.internal.persistence.PlaybackSettingsManager.getShuffleMode(),
        playbackSpeed = aman.playbackengine.enginecore.internal.persistence.PlaybackSettingsManager.getSpeed(),
        playbackPitch = aman.playbackengine.enginecore.internal.persistence.PlaybackSettingsManager.getPitch()
    ))
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _currentMedia3Player = MutableStateFlow<Player?>(null)
    val currentMedia3Player: StateFlow<Player?> = _currentMedia3Player.asStateFlow()

    // --- Restoration State (Persistence Handoff) ---
    private var pendingRestorePosition: Long? = null
    private var restoredMediaUid: String? = null

    /**
     * Updates the underlying [PlaybackState].
     * 
     * PERSISTENCE HANDOFF INVALIDATION: If the active media item changes to something 
     * other than the restored track, we must purge the [pendingRestorePosition]. 
     * This prevents a previous session's position from being accidentally applied 
     * to a new track selection.
     */
    fun updateState(transform: (PlaybackState) -> PlaybackState) {
        val oldState = _state.value
        val newState = transform(oldState)

        // Restoration Invalidation: If the media changes to something other than what was restored,
        // we must drop the restored position to prevent it being applied to the wrong track.
        if (newState.currentMedia?.uid != oldState.currentMedia?.uid) {
            if (newState.currentMedia?.uid != restoredMediaUid) {
                pendingRestorePosition = null
                restoredMediaUid = null
            }
        }
        _state.value = newState
    }

    /**
     * Specifically handles media item transitions.
     */
    fun onMediaTransitioned(media: PlayableMedia?) {
        updateState { it.copy(
            currentMedia = media,
            currentPositionMs = 0L // Instant UI snap to beginning
        ) }
    }

    /**
     * Updates the playback position and duration for the UI.
     */
    fun onPositionUpdated(positionMs: Long, durationMs: Long) {
        updateState { it.copy(
            currentPositionMs = positionMs,
            durationMs = durationMs
        ) }
    }

    /**
     * Updates engine-level status.
     */
    fun onEngineStatusChanged(engineState: EngineState, isMpv: Boolean) {
        updateState { it.copy(
            engineState = engineState,
            isMpvActive = isMpv
        ) }
    }

    /**
     * Updates the active Media3 player for session integration.
     */
    fun onPlayerChanged(player: Player?) {
        _currentMedia3Player.value = player
    }

    /**
     * Manually sets the pending restore position (used for seeking before engine is ready).
     */
    fun setPendingRestorePosition(positionMs: Long) {
        pendingRestorePosition = positionMs
    }

    /**
     * Consumes and returns the pending restore position.
     * Used by engines during cold-start preparation.
     * 
     * This nulls out the position immediately after returning it 
     * to ensure it is only used once per session.
     */
    fun consumeRestorePosition(): Long? {
        val pos = pendingRestorePosition
        pendingRestorePosition = null
        return pos
    }

    /**
     * Performs the session restoration logic.
     * 
     * HIJACK PREVENTION: We only restore the disk state if the current [queue] 
     * is empty. If the user has already manually loaded a new playlist before 
     * the database read finishes, we abort the restoration to avoid overwriting 
     * their new session.
     * 
     * @return true if the restoration was successful (not hijacked).
     */
    suspend fun restoreSession(queue: MediaQueue, snapshot: QueueSnapshot): Boolean {
        // Hijack Prevention: Only restore if the user hasn't already started a new session.
        if (!queue.isEmpty()) return false

        queue.restoreFromSnapshot(snapshot)
        
        val currentMedia = snapshot.currentUid?.let { uid ->
            snapshot.masterList.find { it.uid == uid } ?: snapshot.shuffledList.find { it.uid == uid }
        }

        pendingRestorePosition = snapshot.positionMs
        restoredMediaUid = currentMedia?.uid

        updateState { it.copy(
            currentMedia = currentMedia,
            currentPositionMs = snapshot.positionMs,
            durationMs = currentMedia?.durationMs ?: 0L,
            queue = queue.items.value
        ) }
        
        return true
    }
}
