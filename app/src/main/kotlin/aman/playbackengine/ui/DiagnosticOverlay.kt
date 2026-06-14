package aman.playbackengine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.os.Debug
import aman.playbackengine.enginecore.audio.AudioController
import aman.playbackengine.enginecore.video.VideoController
import aman.playbackengine.enginecore.PlaybackManager
import aman.playbackengine.enginecore.EngineState
import aman.playbackengine.enginecore.PlaybackState
import aman.playbackengine.enginecore.PlaybackAuthority
import aman.playbackengine.enginecore.BasePlaybackController
import aman.playbackengine.enginecore.LogEntry
import aman.playbackengine.enginecore.PlaybackLogger
import aman.playbackengine.enginecore.PlaybackEngine
import aman.playbackengine.engineexoplayer.audio.CrossfadeExoEngine
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun DiagnosticOverlay(
    audioController: AudioController,
    videoController: VideoController,
    onClose: () -> Unit
) {
    val logs by PlaybackLogger.logs.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    fun copyLogs(count: Int = -1) {
        val listToCopy = if (count == -1) logs else logs.takeLast(count)
        val text = listToCopy.joinToString("\n") { "[${it.timestamp}] ${it.tag}: ${it.message}" }
        clipboardManager.setText(AnnotatedString(text))
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .fillMaxWidth(0.95f)
                .height(550.dp) // Taller for stats
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                },
            shape = MaterialTheme.shapes.medium,
            color = Color.Black.copy(alpha = 0.9f),
            tonalElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("System Diagnostics", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
                    }
                }

                MemoryMonitorSection()
                
                HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
                
                // Real-time Engine Stats
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    val engine = audioController.getEngine()
                    
                    // Always show unified Audio status
                    LiveEngineStatus("Audio", audioController)
                    
                    // Show extra Crossfade details if active
                    if (engine is CrossfadeExoEngine) {
                        val stats by engine.diagnostics.collectAsState()
                        CrossfadeStats(stats)
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    LiveEngineStatus("Video", videoController)
                }
                
                HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)

                // Live Logs View
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), reverseLayout = true) {
                        items(logs.reversed()) { entry ->
                            LogEntryItem(entry)
                        }
                    }
                    
                    // Floating Log Actions
                    Row(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                        IconButton(onClick = { PlaybackLogger.clear() }, modifier = Modifier.size(32.dp).background(Color.DarkGray, MaterialTheme.shapes.small)) {
                            Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = { copyLogs(-1) }, modifier = Modifier.size(32.dp).background(Color.DarkGray, MaterialTheme.shapes.small)) {
                            Icon(Icons.Default.ContentCopy, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CrossfadeStats(stats: CrossfadeExoEngine.ExoDiagnostic) {
    Column(modifier = Modifier.fillMaxWidth().background(Color.DarkGray.copy(alpha = 0.3f)).padding(4.dp)) {
        Text("Crossfade Engine: ${if (stats.isTransitioning) "TRANSITIONING" else "IDLE"}", 
            color = if (stats.isTransitioning) Color.Green else Color.Gray, 
            style = MaterialTheme.typography.labelSmall)
        
        Row(modifier = Modifier.fillMaxWidth()) {
            // Primary Player Stats
            Column(modifier = Modifier.weight(1f).padding(2.dp)) {
                Text("Primary ${if (stats.primaryIsPlayer1) "(P1)" else "(P2)"}", color = Color.Cyan, style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(progress = stats.currentVol, modifier = Modifier.fillMaxWidth().height(4.dp), color = Color.Cyan)
                Text("Vol: ${(stats.currentVol*100).toInt()}% Pos: ${stats.currentPos/1000}s", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            // Background Player Stats
            Column(modifier = Modifier.weight(1f).padding(2.dp)) {
                Text("Background ${if (stats.primaryIsPlayer1) "(P2)" else "(P1)"}", color = Color.Magenta, style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(progress = stats.backgroundVol, modifier = Modifier.fillMaxWidth().height(4.dp), color = Color.Magenta)
                Text("Vol: ${(stats.backgroundVol*100).toInt()}% Pos: ${stats.backgroundPos/1000}s", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun LiveEngineStatus(label: String, controller: PlaybackAuthority) {
    val state by controller.state.collectAsState()
    val position = state.currentPositionMs
    val duration = state.durationMs
    val engineState = state.engineState

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("$label: ", color = Color.Gray, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
        Text(engineState::class.simpleName?.take(6) ?: "Unknown", color = Color.Cyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(50.dp))
        val progress = if (duration > 0) position.toFloat() / duration else 0f
        LinearProgressIndicator(progress = progress, modifier = Modifier.weight(1f).height(4.dp).padding(horizontal = 4.dp), color = Color.Green, trackColor = Color.DarkGray)
        Text("${position/1000}/${duration/1000}s", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
    
    // Multiplier Visualization
    Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        val baseVol = state.currentVolume
        val rgMult = state.replayGainMultiplier
        val fadeMult = state.sleepFadeMultiplier
        val final = baseVol * rgMult * fadeMult
        Text(
            text = "Vol: ${"%.2f".format(baseVol)} × RG: ${"%.2f".format(rgMult)} × Fade: ${"%.2f".format(fadeMult)} = ${"%.2f".format(final)}",
            color = Color.Yellow,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 40.dp)
        )
    }
}

@Composable
private fun LogEntryItem(entry: LogEntry) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.timestamp, color = Color.DarkGray, style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.width(6.dp))
            val tagColor = when {
                entry.tag == "JOURNAL" -> Color.Green
                entry.tag.contains("MPV") -> Color.Yellow
                entry.tag.contains("CROSSFADE") -> Color.Cyan
                entry.tag == "PB_DEBUG" -> Color.Magenta
                else -> Color.Gray
            }
            Text(text = entry.tag, color = tagColor, style = MaterialTheme.typography.labelSmall)
        }
        val messageColor = if (entry.tag == "JOURNAL") Color.Green.copy(alpha = 0.9f) else Color.White
        Text(text = entry.message, color = messageColor, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun MemoryMonitorSection() {
    var jvmHeap by remember { mutableLongStateOf(0L) }
    var nativeHeap by remember { mutableLongStateOf(0L) }
    var maxHeap by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            val runtime = Runtime.getRuntime()
            jvmHeap = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            maxHeap = runtime.maxMemory() / (1024 * 1024)
            nativeHeap = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
            delay(1000)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text("Memory Usage:", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("JVM: ${jvmHeap}MB / ${maxHeap}MB", 
                color = if (jvmHeap > maxHeap * 0.8) Color.Red else Color.Green,
                style = MaterialTheme.typography.bodySmall)
            Text("Native: ${nativeHeap}MB", 
                color = Color.Cyan,
                style = MaterialTheme.typography.bodySmall)
        }
        
        val progress = if (maxHeap > 0) jvmHeap.toFloat() / maxHeap else 0f
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(2.dp).padding(top = 2.dp),
            color = if (jvmHeap > maxHeap * 0.8) Color.Red else Color.Green,
            trackColor = Color.DarkGray
        )
    }
}
