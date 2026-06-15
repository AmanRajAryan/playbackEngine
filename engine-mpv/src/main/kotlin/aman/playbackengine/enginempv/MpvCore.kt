package aman.playbackengine.enginempv

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import aman.playbackengine.enginecore.PlaybackLogger
import aman.playbackengine.enginecore.DecoderPolicy
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.abs

/**
 * Represents the current playback state of the native MPV core.
 */
data class MpvState(
    val isIdle: Boolean = true,
    val isBuffering: Boolean = false,
    val isPaused: Boolean = true,
    val isEnded: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Double = 1.0,
    val currentIndex: Int = 0,
    val playlist: List<MediaItem> = emptyList(),
    val decoderPolicy: DecoderPolicy = DecoderPolicy.HW_PLUS
)

/**
 * Low-level wrapper around the native libmpv implementation.
 * Orchestrates JNI calls, property observation, and reactive state updates.
 */
class MpvCore(private val context: Context) {
    private val TAG = "MPV_CORE"
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        // Global lock to prevent simultaneous native init/destroy across all instances.
        private val nativeMutex = Mutex()
    }

    private val _state = MutableStateFlow(MpvState())
    
    /**
     * Reactive flow of the current engine state.
     */
    val state: StateFlow<MpvState> = _state.asStateFlow()

    private val _playWhenReady = MutableStateFlow(false)
    val playWhenReady: StateFlow<Boolean> = _playWhenReady.asStateFlow()

    private val nativeInitJob: Deferred<Unit>
    private var lastSeekTime = 0L
    private var isSurfaceAttached = false
    private var autoplay = true
    private var repeatMode = aman.playbackengine.enginecore.RepeatMode.OFF
    private var currentBaseVolume = 1.0f
    private var volumeMultiplier = 1.0f

    /**
     * Enables or disables automatic advancing to the next item in the playlist.
     */
    fun setAutoplay(enabled: Boolean) {
        autoplay = enabled
    }

    fun setRepeatMode(mode: aman.playbackengine.enginecore.RepeatMode) {
        repeatMode = mode
        runGuarded {
            MPVLib.setPropertyString("loop-file", if (mode == aman.playbackengine.enginecore.RepeatMode.ONE) "inf" else "no")
        }
    }

    fun setPlaybackSpeed(speed: Float) = runGuarded {
        MPVLib.setPropertyDouble("speed", speed.toDouble())
    }

    fun setPlaybackPitch(pitch: Float) = runGuarded {
        // MPV supports direct pitch property to scale output frequency
        MPVLib.setPropertyDouble("pitch", pitch.toDouble())
    }

    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            PlaybackLogger.log(TAG, "Native: [$prefix] $text")
        }
    }

    var onTracksChanged: (() -> Unit)? = null

    private val eventObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {
            if (property == "track-list") {
                onTracksChanged?.invoke()
            }
        }
        
        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> {
                    updateState { it.copy(isPaused = value, isIdle = false) }
                    _playWhenReady.value = !value
                }
                "core-idle" -> updateState { it.copy(isIdle = value) }
                "paused-for-cache" -> updateState { it.copy(isBuffering = value) }
                "eof-reached" -> {
                    if (value) {
                        handleEndOfFile()
                    }
                }
            }
        }
        
        override fun eventProperty(property: String, value: Long) {}
        
        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> {
                    val newPosMs = (value * 1000).toLong()
                    val current = _state.value
                    
                    val isSeeking = System.currentTimeMillis() - lastSeekTime < 200
                    val hasMovedEnough = abs(newPosMs - current.positionMs) >= 500
                    
                    if (isSeeking || hasMovedEnough) {
                        if (!current.isEnded) {
                            updateState { it.copy(positionMs = newPosMs, isIdle = false) }
                        }
                    }
                }
                "duration" -> updateState { it.copy(durationMs = (value * 1000).toLong()) }
                "speed" -> updateState { it.copy(speed = value) }
            }
        }
        
        override fun eventProperty(property: String, value: String) {}
        override fun eventProperty(property: String, value: `is`.xyz.mpv.MPVNode) {}
        
        override fun event(eventId: Int, data: `is`.xyz.mpv.MPVNode) {}
    }

    private fun handleEndOfFile() {
        val s = _state.value
        val isLast = s.currentIndex >= s.playlist.size - 1
        
        // Native REPEAT is handled by 'loop-file' property automatically.
        // We only handle AUTO transitions here.
        if (repeatMode == aman.playbackengine.enginecore.RepeatMode.ONE) return

        pause()

        if (isLast) {
            if (repeatMode == aman.playbackengine.enginecore.RepeatMode.ALL && s.playlist.size > 1) {
                skipToIndex(0)
                play()
            } else {
                updateState { it.copy(isEnded = true, isPaused = true) }
            }
        } else if (autoplay) {
            skipToNext()
            play()
        } else {
            updateState { it.copy(isPaused = true, isEnded = true) }
        }
    }

    init {
        nativeInitJob = scope.async(Dispatchers.IO) {
            nativeMutex.withLock {
                initializeNative()
            }
        }
    }

    private fun runGuarded(block: () -> Unit) {
        scope.launch {
            try {
                nativeInitJob.await()
                block()
            } catch (e: Exception) {
                PlaybackLogger.log(TAG, "Command suppressed: Native MPV core not available.")
            }
        }
    }

    private fun initializeNative() {
        try {
            Utils.copyAssets(context)
            MPVLib.create(context)
            val configDir = context.filesDir.path
            val cacheDir = context.cacheDir.path
            MPVLib.setOptionString("config", "yes")
            MPVLib.setOptionString("config-dir", configDir)
            MPVLib.setOptionString("gpu-shader-cache-dir", cacheDir)
            MPVLib.setOptionString("force-window", "yes")
            
            val cacheMegs = 64
            MPVLib.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
            MPVLib.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
            
            MPVLib.setOptionString("audio-wait-open", "0")
            MPVLib.setOptionString("video-sync", "audio")
            MPVLib.setOptionString("ao-ignore-vo-underrun", "yes")                
            MPVLib.setOptionString("vo", "gpu-next")
            MPVLib.setOptionString("gpu-context", "android")
            
            MPVLib.setOptionString("keep-open", "yes") 
            MPVLib.setOptionString("gapless-audio", "yes")
            
            // --- Volume Foundation ---
            // 1. Disable native RG so the library is the single source of truth.
            // 2. Raise max volume to 200 (Software Pre-Amp) to support ReplayGain boosts.
            MPVLib.setOptionString("replaygain", "no")
            MPVLib.setOptionString("volume-max", "200")
            
            MPVLib.setOptionString("hwdec", "mediacodec")
            MPVLib.setOptionString("hwdec-codecs", "all")
            MPVLib.setOptionString("msg-level", "all=warn")
            
            MPVLib.addLogObserver(logObserver)
            MPVLib.addObserver(eventObserver)
            MPVLib.init()
            
            MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("core-idle", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
            
            PlaybackLogger.log(TAG, "MpvCore: Native library initialized.")
        } catch (e: Exception) {
            PlaybackLogger.log(TAG, "CRITICAL: MPV Init Failed: ${e.message}")
        }
    }

    private fun updateState(transform: (MpvState) -> MpvState) {
        _state.value = transform(_state.value)
    }

    // --- Commands (Guarded) ---
    
    fun command(vararg args: String) = runGuarded {
        MPVLib.command(*args)
    }
    
    fun setPropertyString(property: String, value: String) = runGuarded {
        MPVLib.setPropertyString(property, value)
    }
    
    fun getPropertyString(property: String): String? = runCatching {
        MPVLib.getPropertyString(property)
    }.getOrNull()

    /**
     * Sets the hardware decoding strategy.
     */
    fun setDecoderPolicy(policy: DecoderPolicy) = runGuarded {
        updateState { it.copy(decoderPolicy = policy) }
        val hwdecStr = when (policy) {
            DecoderPolicy.HW_PLUS -> "mediacodec"
            DecoderPolicy.HW -> "mediacodec-copy"
            DecoderPolicy.SW -> "no"
        }
        MPVLib.setPropertyString("hwdec", hwdecStr)
    }

    /**
     * Loads a playlist and starts playback at the specified index.
     */
    fun loadPlaylist(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long, autoPlay: Boolean = true) = runGuarded {
        if (mediaItems.isEmpty() || startIndex !in mediaItems.indices) return@runGuarded
        
        MPVLib.command("stop")
        
        updateState { it.copy(
            playlist = mediaItems, 
            currentIndex = startIndex, 
            isIdle = false, 
            isEnded = false,
            isPaused = !autoPlay,
            positionMs = startPositionMs
        )}

        MPVLib.setPropertyString("vid", if (isSurfaceAttached) "auto" else "no")
        MPVLib.setPropertyBoolean("pause", !autoPlay)

        val item = mediaItems[startIndex]
        val path = resolveUri(Uri.parse(item.localConfiguration?.uri.toString())) ?: item.localConfiguration?.uri.toString()
        
        val startSec = startPositionMs / 1000.0
        MPVLib.setOptionString("start", String.format(java.util.Locale.US, "%.3f", startSec))
        
        applyFinalVolume()
        MPVLib.command("loadfile", path)
        
        item.localConfiguration?.subtitleConfigurations?.firstOrNull()?.uri?.let { subUri ->
            val resolvedSubPath = resolveUri(subUri) ?: subUri.toString()
            MPVLib.command("sub-add", resolvedSubPath)
        }
    }

    private fun loadSingleTrack(item: MediaItem) {
        val path = resolveUri(Uri.parse(item.localConfiguration?.uri.toString())) ?: item.localConfiguration?.uri.toString()
        
        MPVLib.setOptionString("start", "0")
        MPVLib.command("loadfile", path, "replace")
        
        item.localConfiguration?.subtitleConfigurations?.firstOrNull()?.uri?.let { subUri ->
            val resolvedSubPath = resolveUri(subUri) ?: subUri.toString()
            MPVLib.command("sub-add", resolvedSubPath)
        }
        
        MPVLib.setPropertyBoolean("pause", false) 
    }

    /**
     * Replaces the active playlist and index without interrupting the native decoder.
     */
    fun updatePlaylist(mediaItems: List<MediaItem>, newCurrentIndex: Int) {
        updateState { 
            it.copy(playlist = mediaItems, currentIndex = newCurrentIndex) 
        }
    }

    /**
     * Appends a media item to the current playlist.
     */
    fun append(mediaItem: MediaItem) {
        updateState { it.copy(playlist = it.playlist + mediaItem) }
    }

    /**
     * Inserts a media item at the specified index.
     */
    fun addQueueItem(index: Int, mediaItem: MediaItem) {
        updateState { 
            val newList = it.playlist.toMutableList()
            newList.add(index.coerceIn(0, newList.size), mediaItem)
            val newIndex = if (index <= it.currentIndex) it.currentIndex + 1 else it.currentIndex
            it.copy(playlist = newList, currentIndex = newIndex)
        }
    }

    /**
     * Removes a media item at the specified index.
     */
    fun removeQueueItem(index: Int) {
        var itemToLoad: MediaItem? = null
        var wasPlaying = false

        updateState {
            if (index !in it.playlist.indices) return@updateState it
            wasPlaying = !it.isPaused
            
            val newList = it.playlist.toMutableList()
            newList.removeAt(index)
            
            val isRemovingCurrent = index == it.currentIndex
            val newIndex = when {
                index < it.currentIndex -> it.currentIndex - 1
                index == it.currentIndex -> index.coerceIn(0, newList.size - 1)
                else -> it.currentIndex
            }
            
            if (isRemovingCurrent) {
                if (newList.isNotEmpty()) {
                    itemToLoad = newList[newIndex]
                } else {
                    runGuarded { MPVLib.command("stop") }
                }
            }
            
            it.copy(playlist = newList, currentIndex = newIndex)
        }

        itemToLoad?.let { 
            runGuarded {
                loadSingleTrack(it)
                if (!wasPlaying) pause()
            }
        }
    }

    /**
     * Moves a media item from one index to another.
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        updateState {
            if (fromIndex !in it.playlist.indices || toIndex !in it.playlist.indices) return@updateState it
            
            val newList = it.playlist.toMutableList()
            val item = newList.removeAt(fromIndex)
            newList.add(toIndex, item)
            
            var newIndex = it.currentIndex
            if (fromIndex == it.currentIndex) {
                newIndex = toIndex
            } else if (fromIndex < it.currentIndex && toIndex >= it.currentIndex) {
                newIndex--
            } else if (fromIndex > it.currentIndex && toIndex <= it.currentIndex) {
                newIndex++
            }
            
            it.copy(playlist = newList, currentIndex = newIndex)
        }
    }

    /**
     * Clears the current playlist and stops playback.
     */
    fun clearQueue() = runGuarded {
        updateState { it.copy(playlist = emptyList(), currentIndex = 0, isEnded = true) }
        MPVLib.command("stop")
    }

    fun play() = runGuarded { 
        MPVLib.setPropertyBoolean("pause", false) 
    }

    fun pause() = runGuarded { 
        MPVLib.setPropertyBoolean("pause", true) 
    }

    fun stop() = runGuarded {
        MPVLib.command("stop")
        updateState { it.copy(isIdle = true, isPaused = true) }
    }

    /**
     * Restarts the current track from the beginning.
     */
    fun replay() = runGuarded {
        val s = _state.value
        if (s.playlist.isNotEmpty()) {
            loadSingleTrack(s.playlist[s.currentIndex])
            updateState { it.copy(isEnded = false, isPaused = false, positionMs = 0L) }
        }
    }

    fun seekTo(positionMs: Long) = runGuarded {
        if (positionMs < 0) return@runGuarded
        lastSeekTime = System.currentTimeMillis()
        updateState { it.copy(positionMs = positionMs, isIdle = false, isEnded = false) }
        MPVLib.command("seek", (positionMs / 1000.0).toString(), "absolute")
    }

    fun skipToIndex(index: Int) = runGuarded {
        val s = _state.value
        if (index in s.playlist.indices) {
            updateState { it.copy(currentIndex = index, positionMs = 0L, isEnded = false) }
            loadSingleTrack(s.playlist[index])
        }
    }

    fun skipToNext() = runGuarded {
        val s = _state.value
        if (s.currentIndex < s.playlist.size - 1) {
            val nextIndex = s.currentIndex + 1
            updateState { it.copy(currentIndex = nextIndex, positionMs = 0L, isEnded = false) }
            loadSingleTrack(s.playlist[nextIndex])
        }
    }

    fun skipToPrevious() = runGuarded {
        val s = _state.value
        if (s.currentIndex > 0) {
            val prevIndex = s.currentIndex - 1
            updateState { it.copy(currentIndex = prevIndex, positionMs = 0L, isEnded = false) }
            loadSingleTrack(s.playlist[prevIndex])
        }
    }

    fun setVolume(volume: Float) = runGuarded {
        currentBaseVolume = volume
        applyFinalVolume()
    }

    fun setVolumeMultiplier(multiplier: Float) = runGuarded {
        volumeMultiplier = multiplier
        applyFinalVolume()
    }

    private fun applyFinalVolume() {
        val linearMultiplier = (currentBaseVolume * volumeMultiplier).toDouble()
        // MPV uses a cubic curve for the 'volume' property: (level/100)^3.
        // To achieve linear behavior (matching ExoPlayer), we pass the cube root 
        // of our desired multiplier.
        val correctedVolume = Math.pow(linearMultiplier, 1.0 / 3.0) * 100.0
        MPVLib.setPropertyDouble("volume", correctedVolume)
    }

    /**
     * Attaches the native renderer to an Android Surface.
     */
    fun attachSurface(surface: Surface) = runGuarded {
        isSurfaceAttached = true
        MPVLib.attachSurface(surface)
        MPVLib.setPropertyString("vid", "auto")
    }
    
    /**
     * Detaches the native renderer from the current Surface.
     */
    fun detachSurface() = runGuarded {
        isSurfaceAttached = false
        MPVLib.setPropertyString("vid", "no")
        MPVLib.detachSurface()
    }

    /**
     * Informs the native core of a surface size change.
     */
    fun notifySurfaceChanged(width: Int, height: Int) = runGuarded {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }
    
    /**
     * Stops playback and prepares the core for a period of inactivity.
     */
    fun release() = runGuarded {
        MPVLib.command("stop")
        detachSurface()
        updateState { MpvState(isIdle = true, isPaused = true, decoderPolicy = it.decoderPolicy) }
    }

    /**
     * Destroys the native core. Must be called before app exit.
     */
    fun destroy() = runGuarded {
        destroyNative()
    }

    /**
     * Synchronously destroys the native core.
     */
    fun destroyBlocking() = kotlinx.coroutines.runBlocking {
        nativeInitJob.await()
        nativeMutex.withLock {
            destroyNative()
        }
    }

    private fun destroyNative() {
        PlaybackLogger.log(TAG, "MpvCore: Destroying native library...")
        MPVLib.removeLogObserver(logObserver)
        MPVLib.removeObserver(eventObserver)
        MPVLib.command("stop")
        MPVLib.detachSurface()
        MPVLib.destroy()
    }

    internal fun resolveUri(uri: Uri): String? {
        if (uri.scheme == null) return null
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> openContentFd(uri)
            else -> uri.toString()
        }
    }

    private fun openContentFd(uri: Uri): String? {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.detachFd()?.let { "fd://$it" }
        }.getOrNull()
    }
}
