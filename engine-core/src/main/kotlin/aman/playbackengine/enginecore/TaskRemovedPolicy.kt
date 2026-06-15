package aman.playbackengine.enginecore

/**
 * Defines what happens to a playback stream when the user swipes the app away from recent apps.
 */
enum class TaskRemovedPolicy {
    /**
     * Keep playing as a foreground service (Standard for music).
     */
    KEEP_PLAYING,

    /**
     * Pause the playback, but keep the engine in memory.
     */
    PAUSE,

    /**
     * Completely stop playback and release engine resources (Best for video).
     */
    RELEASE
}
