package aman.playbackengine.enginecore

import android.content.Context
import aman.playbackengine.enginecore.audio.AudioController
import aman.playbackengine.enginecore.video.VideoController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand

/**
 * The primary entry point for the playback library.
 * 
 * PlaybackManager coordinates the high-level orchestration of Audio and Video streams.
 * It uses an auto-initialization strategy via an internal ContentProvider, meaning it
 * is ready to use immediately without requiring manual initialization in the app's onCreate.
 * 
 * Key Responsibilities:
 * - **Controller Hosting**: Provides singleton access to [AudioController] and [VideoController].
 * - **Engine Registration**: Manages the registration of [EngineProvider] plugins.
 * - **Global Coordination**: Handles mutual pause logic and global settings like ReplayGain.
 * - **Session Management**: Hosts the global [MediaSession] used for OS-level integration.
 */
object PlaybackManager {
    private const val TAG = "PlaybackManager"

    // --- Configuration ---
    private val providers = mutableMapOf<EngineType, EngineProvider>()
    
    /**
     * The engine type that will be used by default when no specific engine is requested.
     */
    var defaultEngineType = EngineType.EXOPLAYER

    // --- Lifecycle Policies ---
    /**
     * Defines how the audio stream behaves when the app is swiped away from recents.
     */
    var audioTaskRemovedPolicy = TaskRemovedPolicy.KEEP_PLAYING
    
    /**
     * Defines how the video stream behaves when the app is swiped away from recents.
     */
    var videoTaskRemovedPolicy = TaskRemovedPolicy.RELEASE

    // --- ReplayGain Configuration ---
    /**
     * Global mode for loudness normalization.
     */
    var replayGainMode: ReplayGainMode = ReplayGainMode.OFF
    
    /**
     * Global pre-amplification gain in decibels.
     */
    var preAmpGainDb: Double = 0.0
    
    /**
     * Grace period in milliseconds before destroying the MPV native core after release.
     * Use 0 for instant, -1 for Never. Default is 10 seconds.
     */
    var mpvGracePeriodMs: Long = 10000L
    
    // --- MPV Singleton Ownership ---
    private var currentMpvOwner: PlaybackAuthority? = null

    /**
     * Global crossfade duration in milliseconds for supported audio engines.
     */
    var crossfadeDurationMs: Long = 10000L

    // --- Controllers (Smart Lazy) ---
    private var internalContext: Context? = null

    /**
     * The singleton instance of the Audio stream controller.
     * Initialized lazily on first access using the auto-captured ApplicationContext.
     */
    val audio by lazy { 
        PlaybackLogger.log("PlaybackManager", "AudioController: Instance created (Lazy)")
        val controller = AudioController(internalContext ?: throw IllegalStateException("PlaybackLibrary not initialized. Check your AndroidManifest.")) 
        controller.ensureSetup()
        controller
    }
    
    /**
     * The singleton instance of the Video stream controller.
     * Initialized lazily on first access using the auto-captured ApplicationContext.
     */
    val video by lazy { 
        PlaybackLogger.log("PlaybackManager", "VideoController: Instance created (Lazy)")
        val controller = VideoController(internalContext ?: throw IllegalStateException("PlaybackLibrary not initialized. Check your AndroidManifest.")) 
        controller.ensureSetup()
        controller
    }

    /**
     * The global MediaSession shared by both streams to prevent notification popping.
     */
    var mediaSession: androidx.media3.session.MediaSession? = null

    // --- UI State Tracking for Notifications ---
    /**
     * Customizable delegate that determines which stream takes priority in the notification drawer.
     */
    var notificationPriorityProvider: NotificationPriorityProvider = DefaultPriorityProvider()
    
    /**
     * Reactive flow indicating whether the app UI is currently visible to the user.
     */
    val isAppInForeground = MutableStateFlow(false)
    
    /**
     * Set by the UI to indicate which role is currently "primary" on screen.
     * The notification priority engine uses this to show the appropriate "background remote".
     */
    val activeScreenRole = MutableStateFlow<SessionRole?>(null)
    
    // --- Custom Notification Actions ---
    val audioNotificationButtons = MutableStateFlow<List<CommandButton>>(emptyList())
    val videoNotificationButtons = MutableStateFlow<List<CommandButton>>(emptyList())
    
    val customCommandEvents = MutableSharedFlow<SessionCommand>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    /**
     * Returns true if the library has successfully captured the context and is ready for use.
     */
    var isInitialized = false
        private set

    /**
     * Internal auto-initialization called by PlaybackInitializer.
     */
    internal fun internalInit(context: Context) {
        if (isInitialized) return
        internalContext = context.applicationContext
        isInitialized = true
    }

    /**
     * Optional manual initialization. 
     * Now mostly a no-op but kept for backward compatibility and explicit config.
     */
    fun init(context: Context) {
        internalInit(context)
    }

    /**
     * Registers an engine provider plugin.
     */
    fun registerProvider(provider: EngineProvider) {
        providers[provider.type] = provider
    }

    /**
     * Creates an Audio engine of the specified type.
     */
    fun createAudioEngine(context: Context, type: EngineType = defaultEngineType): AudioEngine {
        val provider = providers[type] ?: throw IllegalStateException("No EngineProvider registered for $type")
        return provider.createAudioEngine(context)
    }

    /**
     * Creates a Video engine of the specified type.
     */
    fun createVideoEngine(context: Context, type: EngineType = defaultEngineType): VideoEngine {
        val provider = providers[type] ?: throw IllegalStateException("No EngineProvider registered for $type")
        return provider.createVideoEngine(context)
    }

    /**
     * Returns the engine type opposite to the default.
     */
    fun getAlternativeType(): EngineType {
        return if (defaultEngineType == EngineType.EXOPLAYER) EngineType.MPV else EngineType.EXOPLAYER
    }

    /**
     * Returns the name of the alternative engine.
     */
    fun getAlternativeEngineName(): String {
        return if (defaultEngineType == EngineType.EXOPLAYER) "MPV" else "ExoPlayer"
    }

    /**
     * Global coordinator for Mutual Pause.
     * Prevents audio and video from playing simultaneously unless audio focus loss is ignored.
     */
    fun requestMutualPause(isVideoRequester: Boolean) {
        if (!isInitialized) return
        if (audio.ignoreAudioFocusLoss || video.ignoreAudioFocusLoss) return

        if (isVideoRequester) {
            audio.pause()
        } else {
            video.pause()
        }
    }

    /**
     * Releases the underlying engine resources for all controllers.
     * Note: The manager remains initialized at the process level.
     */
    fun release() {
        if (isInitialized) {
            audio.release()
            video.release()
            
            // Clean up each provider (e.g. destroy MPV native core)
            // This replaces the reflection-based cleanup hack.
            providers.values.forEach { it.onRelease() }
        }
    }
}
