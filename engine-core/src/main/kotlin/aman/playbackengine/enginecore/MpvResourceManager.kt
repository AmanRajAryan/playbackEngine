package aman.playbackengine.enginecore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global coordinator for the native libmpv singleton.
 * Ensures only one [BasePlaybackController] uses MPV at a time.
 */
object MpvResourceManager {
    private val TAG = "MpvResourceManager"

    sealed class LeaseState {
        object Available : LeaseState()
        data class Busy(val owner: PlaybackAuthority) : LeaseState()
    }

    private val _leaseState = MutableStateFlow<LeaseState>(LeaseState.Available)
    val leaseState: StateFlow<LeaseState> = _leaseState.asStateFlow()

    /**
     * Attempts to acquire the MPV lease.
     * If busy, the current owner is signaled to yield.
     */
    fun acquireLease(requester: PlaybackAuthority): Boolean {
        synchronized(this) {
            val current = _leaseState.value
            if (current is LeaseState.Busy) {
                if (current.owner === requester) return true
                
                PlaybackLogger.log(TAG, "MPV Contention! Requesting yield from ${current.owner.javaClass.simpleName}")
                // In a production library, we might use a formal callback. 
                // For now, we use the internal knowledge of how controllers work.
                current.owner.handleMpvRevocation()
            }
            
            _leaseState.value = LeaseState.Busy(requester)
            return true
        }
    }

    fun releaseLease(owner: PlaybackAuthority) {
        synchronized(this) {
            val current = _leaseState.value
            if (current is LeaseState.Busy && current.owner === owner) {
                _leaseState.value = LeaseState.Available
                PlaybackLogger.log(TAG, "MPV Lease released by ${owner.javaClass.simpleName}")
            }
        }
    }
}
