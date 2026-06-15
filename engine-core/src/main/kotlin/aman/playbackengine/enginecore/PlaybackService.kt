package aman.playbackengine.enginecore

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Default implementation of PlaybackService.
 * Uses a single Global MediaSession to avoid notification flickering while switching streams.
 */
class PlaybackService : BasePlaybackService() {

    private val activeSessionRole = kotlinx.coroutines.flow.MutableStateFlow(SessionRole.AUDIO)
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                PlaybackLogger.log("PlaybackService", "Becoming Noisy: Pausing all streams.")
                if (PlaybackManager.isInitialized) {
                    PlaybackManager.audio.pause()
                    PlaybackManager.video.pause()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(becomingNoisyReceiver, filter)

        PlaybackLogger.log("PlaybackService", "onCreate() triggered")
        setupGlobalSession()
        
        // Initial priority check
        if (PlaybackManager.isInitialized) {
            enforcePriority(
                PlaybackManager.audio.state.value,
                PlaybackManager.video.state.value,
                PlaybackManager.audio.currentMedia3Player.value,
                PlaybackManager.video.currentMedia3Player.value,
                PlaybackManager.isAppInForeground.value,
                PlaybackManager.activeScreenRole.value
            )
        }
        
        observeControllerStates()
    }

    private fun setupGlobalSession() {
        if (!PlaybackManager.isInitialized) {
            PlaybackLogger.log("PlaybackService", "setupGlobalSession(): Cancelled - PlaybackManager not initialized")
            return
        }
        if (PlaybackManager.mediaSession != null) return

        PlaybackLogger.log("PlaybackService", "setupGlobalSession(): Initializing Global MediaSession")
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(this, 1, launchIntent, PendingIntent.FLAG_IMMUTABLE)

        val initialPlayer = PlaybackManager.audio.getMedia3Player() ?: PlaybackManager.video.getMedia3Player()
        
        if (initialPlayer != null) {
            val session = MediaSession.Builder(this, initialPlayer)
                .setId("GlobalPlaybackSession")
                .setSessionActivity(pendingIntent)
                .setCallback(object : MediaSession.Callback {
                    override fun onConnect(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo
                    ): MediaSession.ConnectionResult {
                        val connectionResult = super.onConnect(session, controller)
                        val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                        
                        PlaybackManager.audioNotificationButtons.value.forEach { button ->
                            button.sessionCommand?.let { sessionCommands.add(it) }
                        }
                        PlaybackManager.videoNotificationButtons.value.forEach { button ->
                            button.sessionCommand?.let { sessionCommands.add(it) }
                        }
                        
                        return MediaSession.ConnectionResult.accept(
                            sessionCommands.build(),
                            connectionResult.availablePlayerCommands
                        )
                    }

                    override fun onCustomCommand(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        customCommand: androidx.media3.session.SessionCommand,
                        args: android.os.Bundle
                    ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                        PlaybackManager.customCommandEvents.tryEmit(customCommand)
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                        )
                    }

                    override fun onPlaybackResumption(
                        mediaSession: MediaSession,
                        controller: MediaSession.ControllerInfo
                    ): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                        val currentItems = mediaSession.player.currentTimeline.let { timeline ->
                            val items = mutableListOf<androidx.media3.common.MediaItem>()
                            for (i in 0 until timeline.windowCount) {
                                items.add(timeline.getWindow(i, androidx.media3.common.Timeline.Window()).mediaItem)
                            }
                            items
                        }
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(
                                currentItems,
                                mediaSession.player.currentMediaItemIndex,
                                mediaSession.player.currentPosition
                            )
                        )
                    }
                })
                .build()
            PlaybackManager.mediaSession = session
            addSession(session)
        }
    }

    private fun observeControllerStates() {
        if (!PlaybackManager.isInitialized) return

        // We use nested combines to keep each block under 5 parameters.
        // This allows Kotlin to provide typed parameters instead of an Array<Any>.
        val audioStatus = combine(
            PlaybackManager.audio.state,
            PlaybackManager.audio.currentMedia3Player
        ) { state, player -> state to player }

        val videoStatus = combine(
            PlaybackManager.video.state,
            PlaybackManager.video.currentMedia3Player
        ) { state, player -> state to player }

        combine(
            audioStatus,
            videoStatus,
            PlaybackManager.isAppInForeground,
            PlaybackManager.activeScreenRole
        ) { audio, video, isForeground, activeScreen ->
            enforcePriority(
                audioState = audio.first,
                videoState = video.first,
                audioPlayer = audio.second,
                videoPlayer = video.second,
                isForeground = isForeground,
                activeScreen = activeScreen
            )
        }.launchIn(scope)

        combine(
            activeSessionRole,
            PlaybackManager.audioNotificationButtons,
            PlaybackManager.videoNotificationButtons
        ) { role, audioBtns, videoBtns ->
            if (role == SessionRole.AUDIO) audioBtns else videoBtns
        }.onEach { buttons ->
            PlaybackManager.mediaSession?.setCustomLayout(buttons)
        }.launchIn(scope)
    }

    private fun enforcePriority(
        audioState: PlaybackState,
        videoState: PlaybackState,
        audioPlayer: Player?,
        videoPlayer: Player?,
        isForeground: Boolean,
        activeScreen: SessionRole?
    ) {
        val session = PlaybackManager.mediaSession ?: return
        
        // 1. Identify the Priority Role
        val role = if (isForeground && activeScreen != null) {
            if (activeScreen == SessionRole.VIDEO) SessionRole.AUDIO else SessionRole.VIDEO
        } else {
            PlaybackManager.notificationPriorityProvider.getPriorityRole(audioState, videoState, isForeground)
        }

        // 2. Safety Fallback: If chosen role has no media, try the other
        val finalRole = when (role) {
            SessionRole.AUDIO -> if (audioState.currentMedia != null) SessionRole.AUDIO else SessionRole.VIDEO
            SessionRole.VIDEO -> if (videoState.currentMedia != null) SessionRole.VIDEO else SessionRole.AUDIO
            else -> SessionRole.AUDIO
        }

        // 3. Execution: Swap the Player on the EXISTING session
        val targetPlayer = if (finalRole == SessionRole.AUDIO) audioPlayer else videoPlayer

        if (targetPlayer != null && session.player !== targetPlayer) {
            PlaybackLogger.log("PlaybackService", "Switching MediaSession player to ${if (finalRole == SessionRole.AUDIO) "Audio" else "Video"}")
            session.player = targetPlayer
        }
        
        activeSessionRole.value = finalRole
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return PlaybackManager.mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setupGlobalSession()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (PlaybackManager.isInitialized) {
            PlaybackLogger.log("PlaybackService", "onTaskRemoved: Triggering Emergency Save")
            PlaybackManager.audio.triggerSave()
            PlaybackManager.video.triggerSave()
        }
        
        super.onTaskRemoved(rootIntent)
        if (!PlaybackManager.isInitialized) return

        val audioPolicy = PlaybackManager.audioTaskRemovedPolicy
        val videoPolicy = PlaybackManager.videoTaskRemovedPolicy

        handlePolicy(PlaybackManager.audio, audioPolicy)
        handlePolicy(PlaybackManager.video, videoPolicy)
        
        val audioEffectivelyDead = audioPolicy == TaskRemovedPolicy.RELEASE || !PlaybackManager.audio.state.value.isPlaying
        val videoEffectivelyDead = videoPolicy == TaskRemovedPolicy.RELEASE || !PlaybackManager.video.state.value.isPlaying
        
        if (audioEffectivelyDead && videoEffectivelyDead) {
            PlaybackManager.mediaSession?.release()
            PlaybackManager.mediaSession = null
            stopSelf()
        }
    }

    private fun handlePolicy(controller: PlaybackAuthority, policy: TaskRemovedPolicy) {
        when (policy) {
            TaskRemovedPolicy.KEEP_PLAYING -> {}
            TaskRemovedPolicy.PAUSE -> controller.pause()
            TaskRemovedPolicy.RELEASE -> controller.release()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(becomingNoisyReceiver)
        if (PlaybackManager.isInitialized) {
            PlaybackLogger.log("PlaybackService", "onDestroy: Triggering Final Save")
            PlaybackManager.audio.triggerSave()
            PlaybackManager.video.triggerSave()
        }
        PlaybackManager.mediaSession?.release()
        PlaybackManager.mediaSession = null
        super.onDestroy()
    }
}
