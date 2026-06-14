package aman.playbackengine.enginecore

import java.util.LinkedList

/**
 * High-level journal of user actions to diagnose how crashes happen.
 */
object SessionJournal {
    private val steps = LinkedList<String>()
    private const val MAX_STEPS = 20

    @Synchronized
    fun log(step: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val entry = "[$timestamp] $step"
        steps.add(entry)
        if (steps.size > MAX_STEPS) {
            steps.removeFirst()
        }
        PlaybackLogger.log("JOURNAL", step)
    }

    @Synchronized
    fun getSteps(): List<String> = steps.toList()
    
    @Synchronized
    fun getFormattedJournal(): String = steps.joinToString("\n")
}
