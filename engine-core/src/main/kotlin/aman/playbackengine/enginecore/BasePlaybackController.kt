package aman.playbackengine.enginecore

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import aman.playbackengine.enginecore.internal.persistence.QueuePersister
import aman.playbackengine.enginecore.internal.persistence.QueueSnapshot
import aman.playbackengine.enginecore.internal.controller.SleepTimerManager
import aman.playbackengine.enginecore.internal.audio.VolumeResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

import aman.playbackengine.enginecore.internal.controller.PersistenceDelegate
import aman.playbackengine.enginecore.internal.controller.SaveLevel
import aman.playbackengine.enginecore.internal.controller.FocusDelegate
import aman.playbackengine.enginecore.internal.controller.FocusCommand
import aman.playbackengine.enginecore.internal.controller.StateCoordinator
/**
 * Base class for orchestrating media playback across different engine types.
 * 
 * BasePlaybackController manages the high-level logic that is common to both Audio and Video:
 * - **State Management**: Delegated to [StateCoordinator] for unified reactive state.
 * - **Audio Focus**: Delegated to [FocusDelegate] to handle system-level focus changes.
 * - **Queue Logic**: Delegates list operations to an internal [MediaQueue].
 * - **Engine Lifecycle**: Manages the creation, swapping, and release of the underlying [PlaybackEngine].
 * - **Persistence**: Delegated to [PersistenceDelegate] to save and restore the queue and position.
 */
abstract class BasePlaybackController<E : PlaybackEngine>(
    protected val context: Context
) : PlaybackAuthority {
    protected val TAG = this.javaClass.simpleName
    protected val scope = CoroutineScope(Dispatchers.Main + Job())
    
    protected var currentEngine: E? = null
    protected var engineStateJob: Job? = null
    protected var playWhenReadyJob: Job? = null
    protected var initJob: Job? = null
    protected var positionJob: Job? = null
    protected var isReleased = false
    
    private val persistenceDelegate by lazy { 
        PersistenceDelegate(scope, queue, persister, { state.value }, TAG) 
    }

    private val focusDelegate by lazy {
        FocusDelegate(context) { command ->
            when (command) {
                is FocusCommand.Pause -> pause()
                is FocusCommand.Resume -> play()
                is FocusCommand.Duck -> {
                    currentDuckMultiplier = command.multiplier
                    refreshReplayGain() // Re-apply volume with ducking
                }
            }
        }
    }

    private val stateCoordinator by lazy { StateCoordinator(scope) }

    // --- Abstract Hooks ---
    /**
     * Factory method for creating the specific engine type (Audio or Video).
     * @param isAlternative If true, creates the engine opposite to the global default.
     */
    protected abstract fun createEngine(isAlternative: Boolean): E

    private val sleepTimer = SleepTimerManager(
        scope = scope,
        onStateChanged = { state, multiplier ->
            sleepFadeMultiplier = multiplier
            updateState { it.copy(
                sleepTimerState = state,
                sleepFadeMultiplier = multiplier
            ) }
            refreshReplayGain()
        },
        onTimerFinished = { pause() }
    )

    // --- Active Configuration ---
    private var sleepFadeMultiplier: Float = 1.0f

    // --- Reactive State ---
    protected val queue = MediaQueue()
    private val persister by lazy { QueuePersister(context, if (isVideoController) "video_queue.json" else "audio_queue.json") }
    
    /**
     * The unified source of truth for the current playback session.
     */
    override val state: StateFlow<PlaybackState> get() = stateCoordinator.state

    /**
     * Exposes the active Media3 Player instance for session attachment.
     */
    override val currentMedia3Player: StateFlow<Player?> get() = stateCoordinator.currentMedia3Player

    // Convenience state flows for UI observation
    val mediaQueue: StateFlow<List<PlayableMedia>> = queue.items
    val engineState: StateFlow<EngineState> by lazy { state.map { it.engineState }.stateIn(scope, SharingStarted.Eagerly, EngineState.Idle) }
    val currentMedia: StateFlow<PlayableMedia?> by lazy { state.map { it.currentMedia }.stateIn(scope, SharingStarted.Eagerly, null) }
    val isMpvActive: StateFlow<Boolean> by lazy { state.map { it.isMpvActive }.stateIn(scope, SharingStarted.Eagerly, false) }
    val isPlaying: StateFlow<Boolean> by lazy { state.map { it.isPlaying }.stateIn(scope, SharingStarted.Eagerly, false) }

    abstract val isVideoController: Boolean
    protected var currentBaseVolume = 1.0f
    protected var currentDuckMultiplier = 1.0f

    /**
     * If true, focus loss events from the system (e.g., other apps starting playback)
     * will be ignored, and this player will continue to play alongside other audio.
     */
    override var ignoreAudioFocusLoss: Boolean = false
        set(value) { 
            field = value
            focusDelegate.ignoreAudioFocusLoss = value
            if (!value && state.value.isPlaying) {
                focusDelegate.requestFocus()
            }
        }

    private var isSetup = false

    /**
     * Internal setup. Triggered on first access.
     * 
     * IDEMPOTENCY GUARD: [isSetup] ensures that duplicate collectors and 
     * redundant disk I/O (loadQueue) are not triggered if this is called multiple times 
     * (e.g., during rapid 'Play' clicks).
     */
    internal fun ensureSetup() {
        if (isSetup) return
        isSetup = true
        
        focusDelegate.ensureSetup()
        
        // --- System Logic Sync ---
        initJob?.cancel()
        initJob = state.map { it.engineState }.distinctUntilChanged().onEach { state ->
            // Logic moved to setEngine intent-based sync
        }.launchIn(scope)

        // Unified Position Polling
        positionJob?.cancel()
        positionJob = scope.launch {
            while (true) {
                if (state.value.isPlaying) {
                    currentEngine?.let { engine ->
                        stateCoordinator.onPositionUpdated(engine.getCurrentPosition(), engine.getDuration())
                    }
                }
                kotlinx.coroutines.delay(500)
            }
        }

        // Sync queue state
        queue.items.onEach { items ->
            updateState { it.copy(queue = items) }
        }.launchIn(scope)

        // Restore last session
        loadQueue()
    }

    private fun loadQueue() {
        PlaybackLogger.log(TAG, "Persistence: loadQueue() triggered.")
        scope.launch(Dispatchers.IO) {
            val snapshot = persister.load() ?: return@launch
            withContext(Dispatchers.Main) {
                if (stateCoordinator.restoreSession(queue, snapshot)) {
                    PlaybackLogger.log(TAG, "Persistence: Queue Restored: ${snapshot.masterList.size} items.")
                } else {
                    PlaybackLogger.log(TAG, "Persistence: Restoration skipped (Queue not empty).")
                }
            }
        }
    }

    /**
     * Orchestrates the persistence depth based on the requested [SaveLevel].
     */
    internal fun triggerSave(level: SaveLevel = SaveLevel.FULL) {
        val pos = currentEngine?.getCurrentPosition()
        persistenceDelegate.triggerSave(level, pos)
    }

    protected fun updateState(transform: (PlaybackState) -> PlaybackState) {
        stateCoordinator.updateState(transform)
    }

    protected fun ensureEngine(): E {
        ensureSetup()
        val engine = currentEngine
        if (engine == null) {
            PlaybackLogger.log(TAG, "Creating engine on-demand...")
            val newEngine = createEngine(isAlternative = false)
            setEngine(newEngine)
            return newEngine
        }
        return engine
    }

    /**
     * Swaps the active engine between the primary and alternative implementation.
     * @param useAlternative If true, switches to the non-default engine (e.g. MPV).
     */
    override fun toggleAlternativeEngine(useAlternative: Boolean) {
        val newEngine = createEngine(isAlternative = useAlternative)
        setEngine(newEngine)
    }

    open fun setEngine(engine: E) {
        ensureSetup()
        PlaybackLogger.log(TAG, "Swapping to ${engine.javaClass.simpleName}")
        
        // --- Position Restoration Logic ---
        val oldEnginePos = currentEngine?.takeIf { it.playbackState.value !is EngineState.Idle }?.getCurrentPosition()
        val lastPos = oldEnginePos ?: stateCoordinator.consumeRestorePosition() ?: 0L
        val wasPlaying = state.value.isPlaying

        val oldEngine = currentEngine
        currentEngine = null
        if (oldEngine?.type == EngineType.MPV) MpvResourceManager.releaseLease(this)
        oldEngine?.release()
        
        engineStateJob?.cancel()
        
        currentEngine = engine
        if (engine.type == EngineType.MPV) {
            MpvResourceManager.acquireLease(this)
        }
        
        // --- Volume & ReplayGain Synchronization ---
        engine.setVolume(currentBaseVolume * sleepFadeMultiplier * currentDuckMultiplier)
        engine.setRepeatMode(state.value.repeatMode)
        engine.setPlaybackSpeed(state.value.playbackSpeed)
        engine.setPlaybackPitch(state.value.playbackPitch)
        state.value.currentMedia?.let { 
            engine.setVolumeMultiplier(calculateGainMultiplier(it))
        }
        
        onEngineConfigured(engine)
        
        engine.onMediaItemTransition = {
            val index = engine.getCurrentMediaIndex()
            val media = queue.get(index)
            if (media != null) {
                // Handle End-of-Track Sleep Timer
                if (state.value.sleepTimerState is SleepTimerState.EndOfTrack) {
                    PlaybackLogger.log(TAG, "Sleep Timer: Stop at End of Track triggered.")
                    pause()
                    sleepTimer.cancel()
                }

                stateCoordinator.onMediaTransitioned(media)
                
                // Apply ReplayGain + PreAmp
                engine.setVolumeMultiplier(calculateGainMultiplier(media))
                
                PlaybackLogger.log(TAG, "Transitioned to index $index (${media.title})")
                triggerSave(SaveLevel.METADATA)
            }
        }

        stateCoordinator.onEngineStatusChanged(engine.playbackState.value, engine.type == EngineType.MPV)

        engineStateJob = engine.playbackState
            .onEach { engineState -> 
                stateCoordinator.onEngineStatusChanged(engineState, engine.type == EngineType.MPV)
            }
            .launchIn(scope)

        // --- Intent-Based Sync ---
        playWhenReadyJob?.cancel()
        playWhenReadyJob = engine.playWhenReady
            .onEach { playWhenReady ->
                updateState { it.copy(isPlaying = playWhenReady) }
                focusDelegate.onPlayerStateChanged(playWhenReady)
            }
            .launchIn(scope)

        if (!queue.isEmpty()) {
            val currentIndex = state.value.currentMedia?.let { queue.indexOf(it) }?.coerceAtLeast(0) ?: 0
            engine.prepare(queue.items.value, currentIndex, lastPos, autoPlay = wasPlaying)
        }
        
        stateCoordinator.onPlayerChanged(engine.getMedia3Player())
    }

    /**
     * Called when the MPV lease is revoked because another controller needs it.
     */
    override fun handleMpvRevocation() {
        if (currentEngine?.type == EngineType.MPV) {
            PlaybackLogger.log(TAG, "MPV lease revoked! Swapping to ExoPlayer fallback.")
            val exoEngine = createEngine(isAlternative = false)
            setEngine(exoEngine)
        }
    }

    protected open fun onEngineConfigured(engine: E) {}


    // --- Queue Management API ---

    fun prepare(mediaList: List<PlayableMedia>, index: Int = 0, playShuffled: Boolean = false) {
        scope.launch {
            val startIndex = queue.set(mediaList, index, playShuffled)
            val media = queue.get(startIndex)
            
            // Explicitly sync the UI state with the new intent
            updateState { it.copy(
                currentMedia = media,
                isShuffleModeEnabled = playShuffled
            ) }
            
            val engine = ensureEngine()
            
            // Initial ReplayGain application
            media?.let { engine.setVolumeMultiplier(calculateGainMultiplier(it)) }
            
            engine.prepare(queue.items.value, startIndex, 0L, autoPlay = false)
            triggerSave()
            play()
        }
    }

    /**
     * Calculates the linear volume multiplier based on ReplayGain metadata and Global Pre-Amp.
     * Delegated to [VolumeResolver] for the math.
     */
    protected fun calculateGainMultiplier(media: PlayableMedia): Float {
        val rgMultiplier = VolumeResolver.resolveReplayGain(media)
        updateState { it.copy(replayGainMultiplier = rgMultiplier) }
        return rgMultiplier
    }

    /**
     * Injects an item right after the currently playing song in BOTH lists.
     */
    override fun playNext(media: PlayableMedia) {
        scope.launch {
            val wasEmpty = queue.isEmpty()
            val currentUid = state.value.currentMedia?.uid
            val uniqueMedia = queue.insertNext(currentUid, media)
            val newIdx = queue.indexOf(uniqueMedia)
            
            if (wasEmpty) {
                updateState { it.copy(currentMedia = uniqueMedia) }
                val engine = ensureEngine()
                engine.setVolumeMultiplier(calculateGainMultiplier(uniqueMedia))
                engine.prepare(queue.items.value, newIdx, 0L, autoPlay = false)
            } else {
                currentEngine?.addQueueItem(newIdx, uniqueMedia)
            }
            
            PlaybackLogger.log(TAG, "Queue: Injected ${media.title} to Play Next at index $newIdx")
            triggerSave()
        }
    }

    /**
     * Appends a new item to the end of BOTH lists.
     */
    override fun enqueue(media: PlayableMedia) {
        scope.launch {
            val wasEmpty = queue.isEmpty()
            val uniqueMedia = queue.enqueue(media)
            val newIdx = queue.indexOf(uniqueMedia)
            
            if (wasEmpty) {
                updateState { it.copy(currentMedia = uniqueMedia) }
                val engine = ensureEngine()
                engine.setVolumeMultiplier(calculateGainMultiplier(uniqueMedia))
                engine.prepare(queue.items.value, newIdx, 0L, autoPlay = false)
            } else {
                currentEngine?.addQueueItem(newIdx, uniqueMedia)
            }
            
            PlaybackLogger.log(TAG, "Queue: Appended ${media.title} to end at index $newIdx")
            triggerSave()
        }
    }

    /**
     * Inserts an item at a specific position in the queue.
     * Note: For strict sync, prefer playNext() or enqueue().
     */
    fun add(index: Int, media: PlayableMedia) {
        scope.launch {
            // Perform positional insertion
            val uniqueMedia = queue.insertNext(null, media) // Fallback for manual add
            currentEngine?.addQueueItem(index, uniqueMedia)
            PlaybackLogger.log(TAG, "Queue: Added ${media.title} at index $index")
            triggerSave()
        }
    }

    /**
     * Inserts an item at a specific position in the queue.
     */
    fun insert(index: Int, media: PlayableMedia) {
        add(index, media)
    }

    /**
     * Removes the item at the specified index from the queue.
     */
    override fun remove(index: Int) {
        scope.launch {
            val removed = queue.remove(index)
            if (removed != null) {
                val isRemovingCurrent = removed.uid == state.value.currentMedia?.uid
                currentEngine?.removeQueueItem(index)
                
                if (isRemovingCurrent) {
                    // Sync: If the active song was deleted, update the UI to the item that took its place
                    val nextMedia = if (queue.isEmpty()) null else queue.get(index.coerceIn(0, queue.size() - 1))
                    stateCoordinator.onMediaTransitioned(nextMedia)
                    
                    // Apply ReplayGain for the new track
                    nextMedia?.let { currentEngine?.setVolumeMultiplier(calculateGainMultiplier(it)) }
                    PlaybackLogger.log(TAG, "Queue: Active song removed. Synced to: ${nextMedia?.title ?: "Nothing"}")
                }
                
                PlaybackLogger.log(TAG, "Queue: Removed ${removed.title} at index $index")
                triggerSave()
            }
        }
    }

    /**
     * Moves an item from one position to another.
     */
    override fun move(fromIndex: Int, toIndex: Int) {
        scope.launch {
            queue.move(fromIndex, toIndex)
            currentEngine?.moveQueueItem(fromIndex, toIndex)
            
            PlaybackLogger.log(TAG, "Queue: Moved item from $fromIndex to $toIndex")
            triggerSave()
        }
    }

    /**
     * Clears the entire playback queue.
     */
    fun clearQueue() {
        scope.launch {
            queue.clear()
            updateState { it.copy(currentMedia = null) }
            currentEngine?.clearQueue()
            PlaybackLogger.log(TAG, "Queue: Cleared")
            triggerSave()
        }
    }

    /**
     * Toggles the shuffle mode and updates the active list.
     */
    override fun toggleShuffle() {
        scope.launch {
            val currentUid = state.value.currentMedia?.uid
            val newIndex = queue.toggleShuffle(currentUid)
            
            // 1. Update the overall state flow
            updateState { it.copy(
                isShuffleModeEnabled = queue.isShuffleOn(),
                currentMedia = queue.get(newIndex)
            ) }
            
            // 2. Hand off the new reality to the engine seamlessly
            currentEngine?.updatePlaylist(queue.items.value, newIndex)
            
            PlaybackLogger.log(TAG, "Shuffle Toggled: ${queue.isShuffleOn()}. New active index: $newIndex")
            triggerSave()
        }
    }

    /**
     * Cycles through RepeatMode: OFF -> ALL -> ONE -> OFF.
     */
    override fun toggleRepeatMode() {
        val nextMode = when (state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        
        updateState { it.copy(repeatMode = nextMode) }
        currentEngine?.setRepeatMode(nextMode)
        
        PlaybackLogger.log(TAG, "Repeat Mode Toggled: $nextMode")
        triggerSave(SaveLevel.METADATA)
    }

    /**
     * Updates the playback speed.
     */
    override fun setPlaybackSpeed(speed: Float) {
        updateState { it.copy(playbackSpeed = speed) }
        currentEngine?.setPlaybackSpeed(speed)
        triggerSave(SaveLevel.METADATA)
    }

    /**
     * Updates the playback pitch.
     */
    override fun setPlaybackPitch(pitch: Float) {
        updateState { it.copy(playbackPitch = pitch) }
        currentEngine?.setPlaybackPitch(pitch)
        triggerSave(SaveLevel.METADATA)
    }

    /**
     * Forces a fresh randomization of the queue.
     */
    override fun reshuffle() {
        scope.launch {
            val currentUid = state.value.currentMedia?.uid
            queue.reshuffle(currentUid)
            
            // 1. Update the overall state flow
            updateState { it.copy(
                isShuffleModeEnabled = true,
                currentMedia = queue.get(0) // Hoisted to top
            ) }
            
            // 2. Hand off the new reality to the engine seamlessly
            currentEngine?.updatePlaylist(queue.items.value, 0)
            
            PlaybackLogger.log(TAG, "Queue: Explicitly Reshuffled")
            triggerSave()
        }
    }

    /**
     * Skips to a specific index in the current queue without re-shuffling or resetting the list.
     */
    override fun skipToIndex(index: Int) {
        // If user manually skips, cancel "End of Track" sleep timer
        if (state.value.sleepTimerState is SleepTimerState.EndOfTrack) {
            cancelSleepTimer()
        }

        val media = queue.get(index) ?: return
        stateCoordinator.onMediaTransitioned(media)
        
        val engine = ensureEngine()
        engine.setVolumeMultiplier(calculateGainMultiplier(media))
        engine.seekToItem(index, 0L)
        
        // Force play since skipToIndex implies intent to hear the specific song now
        if (!state.value.isPlaying) {
            play()
        }
        
        PlaybackLogger.log(TAG, "Queue: Skipped to index $index (${media.title})")
        
        // Save the playback pointer immediately to ensure session continuity,
        // then queue a debounced full sync for the structural playlist change.
        triggerSave(SaveLevel.METADATA)
        triggerSave(SaveLevel.FULL)
    }

    /**
     * Starts a sleep timer for a specific duration.
     * The timer only ticks when playing. Volume fades in the last 60 seconds.
     */
    override fun startSleepTimer(durationMs: Long) {
        sleepTimer.start(durationMs, isPlayingProvider = { state.value.isPlaying })
    }

    /**
     * Stops playback after the current song finishes.
     */
    override fun startSleepTimerEndOfTrack() {
        sleepTimer.startEndOfTrack()
    }

    /**
     * Cancels any active sleep timer and restores volume.
     */
    override fun cancelSleepTimer() {
        sleepTimer.cancel()
    }

    // --- Playback Commands ---

    override fun togglePlayPause() {
        val engine = ensureEngine()
        if (state.value.isPlaying) pause() else play()
    }

    override fun play() {
        val engine = ensureEngine()
        
        // --- 1. Global Prerequisite: Mutual Pause ---
        PlaybackManager.requestMutualPause(isVideoRequester = isVideoController)

        // --- 2. Audio Focus Validation ---
        // We request focus BEFORE we do anything else audible.
        if (!focusDelegate.requestFocus()) {
            PlaybackLogger.log(TAG, "Focus Denied. Playback cancelled.")
            return
        }

        val currentState = engine.playbackState.value
        
        // --- 3. Handle Special States ---
        if (currentState is EngineState.Ended) {
            engine.replay()
            startService()
            return
        }

        // --- 4. Initial Playback State Sync ---
        if (currentState is EngineState.Uninitialized || currentState is EngineState.Idle) {
            val media = state.value.currentMedia
            if (media != null) {
                val index = queue.indexOf(media).coerceAtLeast(0)
                val engineIndex = engine.getCurrentMediaIndex()
                
                if (engineIndex == -1) {
                    PlaybackLogger.log(TAG, "Cold Start: Preparing and Playing immediately.")
                    val startPos = stateCoordinator.consumeRestorePosition() ?: 0L
                    engine.prepare(queue.items.value, index, startPos, autoPlay = true)
                } else {
                    PlaybackLogger.log(TAG, "Resuming from Idle state (Soft Resume)")
                    val startPos = stateCoordinator.consumeRestorePosition()
                    if (startPos != null) {
                        engine.seekToItem(index, startPos)
                    }
                    engine.play()
                }
            }
        } else {
            // Standard Resume (Paused -> Playing)
            engine.play()
        }

        startService()
    }

    protected fun startService() {
        val intent = Intent(context, PlaybackService::class.java)
        context.startService(intent)
    }

    override fun pause() {
        currentEngine?.pause()
        triggerSave(SaveLevel.METADATA)
    }

    override fun stop() {
        currentEngine?.stop()
        triggerSave(SaveLevel.METADATA)
    }

    override fun seekTo(positionMs: Long) { 
        // 1. Update UI state immediately for responsive feedback.
        stateCoordinator.updateState { it.copy(currentPositionMs = positionMs) }
        
        // 2. Handle position restoration based on engine availability.
        if (currentEngine == null) {
            // Engine not initialized: Cache the seek position to be applied on startup.
            stateCoordinator.setPendingRestorePosition(positionMs)
        } else {
            // Engine active: Apply seek directly to the player.
            currentEngine?.seekTo(positionMs)
        }
        
        triggerSave(SaveLevel.POSITION)
    }

    override fun setVolume(volume: Float) { 
        currentBaseVolume = volume
        updateState { it.copy(currentVolume = volume) }
        currentEngine?.setVolume(volume * sleepFadeMultiplier * currentDuckMultiplier) 
        triggerSave(SaveLevel.METADATA)
    }

    /**
     * Refreshes the volume multiplier for the current engine.
     * Useful when global settings (ReplayGainMode, PreAmp) change or sleep timer fades.
     */
    override fun refreshReplayGain() {
        state.value.currentMedia?.let { media ->
            // 1. Refresh ReplayGain Multiplier
            val rgMultiplier = calculateGainMultiplier(media)
            currentEngine?.setVolumeMultiplier(rgMultiplier)
            
            // 2. Apply Sleep Fade AND Ducking to the BASE volume
            val effectiveBase = currentBaseVolume * sleepFadeMultiplier * currentDuckMultiplier
            currentEngine?.setVolume(effectiveBase)
            
            // 3. Sync UI state
            updateState { it.copy(
                sleepFadeMultiplier = sleepFadeMultiplier
            ) }
        }
    }

    override fun getManualVolume(): Float = currentBaseVolume

    fun getCurrentPosition(): Long = currentEngine?.getCurrentPosition() ?: 0L
    fun getDuration(): Long = currentEngine?.getDuration() ?: 0L
    override fun getMedia3Player(): Player? = currentEngine?.getMedia3Player()
    fun getEngine(): PlaybackEngine? = currentEngine

    override fun release() {
        // Guard against multiple release calls to protect underlying engine resources.
        if (isReleased) return
        isReleased = true
        PlaybackLogger.log(TAG, "Releasing Controller")

        triggerSave(SaveLevel.METADATA)
        focusDelegate.abandonFocus()
        
        // Exhaustive cleanup of background tasks and observers.
        engineStateJob?.cancel()
        playWhenReadyJob?.cancel()
        initJob?.cancel()
        positionJob?.cancel()
        persistenceDelegate.release()

        if (currentEngine?.type == EngineType.MPV) MpvResourceManager.releaseLease(this)
        currentEngine?.release()
        currentEngine = null
        stateCoordinator.onPlayerChanged(null)
        
        // Reset state so service knows we are truly inactive
        stateCoordinator.onEngineStatusChanged(EngineState.Idle, false)
        updateState { it.copy(isPlaying = false) }
        scope.cancel() // Shutdown primary lifecycle scope
    }
}
