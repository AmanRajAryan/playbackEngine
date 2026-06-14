package aman.playbackengine

import android.content.ComponentName
import android.os.Bundle
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import aman.playbackengine.enginecore.EngineState
import aman.playbackengine.enginecore.PlaybackEngine
import aman.playbackengine.enginecore.VideoEngine
import aman.playbackengine.enginecore.PlaybackService
import aman.playbackengine.enginecore.DecoderPolicy
import aman.playbackengine.enginempv.MpvEngine
import aman.playbackengine.ui.theme.ComposeEmptyActivityTheme
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class PureMpvActivity : ComponentActivity() {

    private lateinit var engine: VideoEngine
    private var mediaController: MediaController? = null

    @androidx.media3.common.util.UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize pure MPV
        engine = MpvEngine.getInstance(this)

        // Ensure service is running        val intent = android.content.Intent(this, PlaybackService::class.java)
        startService(intent)

        // Connect to the background service directly (bypassing PlaybackManager)
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            { 
                mediaController = controllerFuture.get()
            },
            MoreExecutors.directExecutor()
        )

        val videoList = MediaStoreScanner.fetchVideo(this)

        setContent {
            ComposeEmptyActivityTheme {
                var isPlaying by remember { mutableStateOf(false) }
                var statusText by remember { mutableStateOf("Idle") }
                var currentDecoder by remember { mutableStateOf(DecoderPolicy.HW_PLUS) }

                LaunchedEffect(Unit) {
                    engine.playbackState.onEach { state ->
                        isPlaying = state is EngineState.Playing
                        statusText = state.javaClass.simpleName
                    }.launchIn(this)
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Pure MPV Debugger", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))

                        Box(modifier = Modifier.fillMaxWidth().height(250.dp).background(Color.Black)) {
                            AndroidView(
                                factory = { ctx ->
                                    SurfaceView(ctx).apply {
                                        engine.setVideoView(this)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Decoder Policy:", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DecoderPolicy.entries.forEach { policy ->
                                FilterChip(
                                    selected = currentDecoder == policy,
                                    onClick = { 
                                        currentDecoder = policy
                                        engine.setDecoderPolicy(policy)
                                    },
                                    label = { Text(policy.name) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Engine State: $statusText", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { 
                                if (videoList.isNotEmpty()) {
                                    engine.prepare(videoList, 0, 0L)
                                }
                            }) { Text("Load Playlist") }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            IconButton(onClick = { engine.play() }, modifier = Modifier.size(64.dp)) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(48.dp))
                            }
                            IconButton(onClick = { engine.pause() }, modifier = Modifier.size(64.dp)) {
                                Icon(Icons.Default.Pause, null, modifier = Modifier.size(48.dp))
                            }
                            IconButton(onClick = { 
                                val player = engine.getMedia3Player()
                                player?.seekToNextMediaItem()
                            }, modifier = Modifier.size(64.dp)) {
                                Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(48.dp))
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        Text("Instructions:", style = MaterialTheme.typography.labelLarge)
                        Text("1. Select a Decoder (SW is default)\n2. Click 'Load Playlist'\n3. Check if audio mutes during HW transitions.", modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaController?.release()
        engine.release()
    }
}
