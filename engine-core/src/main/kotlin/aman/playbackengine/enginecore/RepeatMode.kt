package aman.playbackengine.enginecore

/**
 * Modes for handling queue completion.
 */
enum class RepeatMode {
    /** Stop playback after the last item. */
    OFF,
    
    /** Loop the current media item indefinitely. */
    ONE,
    
    /** Loop back to the first item after the last item completes. */
    ALL
}
