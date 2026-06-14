package aman.playbackengine.enginecore

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Base service responsible for hosting MediaSessions.
 * Consumers can extend this to customize notification behavior.
 */
open class BasePlaybackService : MediaSessionService() {

    protected val TAG = "BasePlaybackService"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlaybackService: onCreate")
        
        val customChannelId = "playback_channel_v2"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                customChannelId,
                "Media Playback",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val provider = androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(customChannelId)
            .setChannelName(androidx.media3.session.R.string.default_notification_channel_name)
            .build()
        
        setMediaNotificationProvider(provider)
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // We ALWAYS stay in the foreground as long as a notification exists.
        // This prevents the 30-second background kill timer.
        super.onUpdateNotification(session, true)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Implementation should decide which session to return (Audio vs Video)
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "PlaybackService: onTaskRemoved (App swiped away)")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "PlaybackService: onDestroy")
        super.onDestroy()
    }
}
