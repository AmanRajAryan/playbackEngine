package aman.playbackengine

import aman.playbackengine.enginecore.SessionJournal
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.system.exitProcess

/**
 * Captures uncaught exceptions and launches the CrashActivity.
 */
class GlobalExceptionHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val intent = Intent(context, CrashActivity::class.java)
            intent.putExtra("error", Log.getStackTraceString(throwable))
            intent.putExtra("journal", SessionJournal.getFormattedJournal())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
            
            // Critical: Give the system time to handle the intent before we kill the process
            Thread.sleep(300) 
        } catch (e: Exception) {
            defaultHandler?.uncaughtException(thread, throwable)
        }
        
        exitProcess(1)
    }

    companion object {
        fun initialize(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(context))
        }
    }
}
