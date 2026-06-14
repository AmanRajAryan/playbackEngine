package aman.playbackengine.enginecore

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

data class LogEntry(
    val timestamp: String,
    val tag: String,
    val message: String
)

object PlaybackLogger {
    private const val MAX_LOGS = 200
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun log(tag: String, message: String) {
        Log.d(tag, message)
        val entry = LogEntry(timeFormat.format(Date()), tag, message)
        val currentList = _logs.value.toMutableList()
        currentList.add(entry)
        if (currentList.size > MAX_LOGS) currentList.removeAt(0)
        _logs.value = currentList
    }

    fun clear() { _logs.value = emptyList() }
}
