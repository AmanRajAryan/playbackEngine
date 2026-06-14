package aman.playbackengine.enginecore

/**
 * Defines which session role should take priority in the notification drawer.
 */
enum class SessionRole {
    AUDIO,
    VIDEO
}

/**
 * Interface that allows the app to define custom notification priority logic.
 */
interface NotificationPriorityProvider {
    /**
     * Called whenever a playback state change occurs.
     * @return The role that should be displayed in the notification drawer, or null if 
     *         the service should use its default internal logic.
     */
    fun getPriorityRole(
        audioState: PlaybackState,
        videoState: PlaybackState,
        isAppInForeground: Boolean
    ): SessionRole?
}

/**
 * Sensible default implementation that prioritizes active streams.
 * Logic: Both Playing -> Audio, One Playing -> That one, Both Paused -> Audio.
 */
class DefaultPriorityProvider : NotificationPriorityProvider {
    override fun getPriorityRole(
        audioState: PlaybackState,
        videoState: PlaybackState,
        isAppInForeground: Boolean
    ): SessionRole {
        return when {
            audioState.isPlaying -> SessionRole.AUDIO
            videoState.isPlaying -> SessionRole.VIDEO
            audioState.currentMedia != null -> SessionRole.AUDIO
            else -> SessionRole.VIDEO
        }
    }
}
