package aman.playbackengine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import aman.playbackengine.enginecore.SessionRole
import aman.playbackengine.enginecore.RepeatMode
import aman.playbackengine.engineexoplayer.ExoEngineProvider
import aman.playbackengine.enginempv.MpvEngineProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import androidx.media3.ui.PlayerView
import aman.playbackengine.enginecore.*
import aman.playbackengine.enginecore.audio.AudioController
import aman.playbackengine.enginecore.video.VideoController
import aman.playbackengine.engineexoplayer.audio.CrossfadeExoEngine
import aman.playbackengine.engineexoplayer.video.ExoVideoEngine
import aman.playbackengine.enginempv.MpvEngine
import aman.playbackengine.ui.theme.ComposeEmptyActivityTheme
import aman.playbackengine.ui.DiagnosticOverlay
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// A dummy global state just for testing our Custom Notification Actions sync
val GlobalTestFavorite = MutableStateFlow(false)

class MainActivity : ComponentActivity() {
    @androidx.media3.common.util.UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        GlobalExceptionHandler.initialize(applicationContext)
        SessionJournal.log("Application Started")

        // 1. Register Engine Providers (Plugins)
        PlaybackManager.registerProvider(ExoEngineProvider())
        PlaybackManager.registerProvider(MpvEngineProvider())
        
        try {
            setContent {
                ComposeEmptyActivityTheme {
                    MainScreen()
                }
            }
        } catch (t: Throwable) {
            val intent = Intent(this, CrashActivity::class.java)
            intent.putExtra("error", "UI Thread Crash: " + Log.getStackTraceString(t))
            intent.putExtra("journal", SessionJournal.getFormattedJournal())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current



    // --- Lifecycle & Screen Tracking ---
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) PlaybackManager.isAppInForeground.value = true
            if (event == Lifecycle.Event.ON_STOP) PlaybackManager.isAppInForeground.value = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var hasPermission by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPermission = it.values.all { it } }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            val perms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            launcher.launch(perms)
        }
    }

    val audioList by produceState(initialValue = emptyList<PlayableMedia>(), hasPermission) {
        if (hasPermission) {
            value = MediaStoreScanner.fetchAudio(context)
        }
    }
    val videoList by produceState(initialValue = emptyList<PlayableMedia>(), hasPermission) {
        if (hasPermission) {
            value = MediaStoreScanner.fetchVideo(context)
        }
    }
    
    val audioController = PlaybackManager.audio
    val videoController = PlaybackManager.video
    
    val audioState by audioController.state.collectAsState()
    val videoState by videoController.state.collectAsState()

    // --- Custom Notification Actions Testing ---
    val isFavorite by GlobalTestFavorite.collectAsState()

    LaunchedEffect(isFavorite, audioState.repeatMode) {
        val favButton = androidx.media3.session.CommandButton.Builder()
            .setDisplayName("Favorite")
            .setIconResId(if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            .setSessionCommand(androidx.media3.session.SessionCommand("ACTION_TOGGLE_FAVORITE", android.os.Bundle.EMPTY))
            .build()
            
        val repeatButton = androidx.media3.session.CommandButton.Builder()
            .setDisplayName("Repeat")
            .setIconResId(
                when (audioState.repeatMode) {
                    aman.playbackengine.enginecore.RepeatMode.ALL -> R.drawable.ic_repeat
                    aman.playbackengine.enginecore.RepeatMode.ONE -> R.drawable.ic_repeat_one
                    else -> R.drawable.ic_arrow_forward // Forward arrow when repeat is OFF
                }
            )
            .setSessionCommand(androidx.media3.session.SessionCommand("ACTION_TOGGLE_REPEAT", android.os.Bundle.EMPTY))
            .build()
            
        PlaybackManager.audioNotificationButtons.value = listOf(favButton, repeatButton)
    }

    LaunchedEffect(Unit) {
        PlaybackManager.customCommandEvents.collect { command ->
            when (command.customAction) {
                "ACTION_TOGGLE_FAVORITE" -> GlobalTestFavorite.value = !GlobalTestFavorite.value
                "ACTION_TOGGLE_REPEAT" -> PlaybackManager.audio.toggleRepeatMode()
            }
        }
    }

    val selectedAudioMedia = audioState.currentMedia
    val selectedVideoMedia = videoState.currentMedia
    
    var expandedController by remember { mutableStateOf<Pair<PlayableMedia, PlaybackAuthority>?>(null) }

    // Sync Screen Role to Manager
    LaunchedEffect(expandedController) {
        PlaybackManager.activeScreenRole.value = when {
            expandedController?.second is VideoController -> SessionRole.VIDEO
            expandedController?.second is AudioController -> SessionRole.AUDIO
            else -> null
        }
    }
    
    var currentTab by remember { mutableStateOf(0) }
    var showLogs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Sync expanded state with auto-advances
    LaunchedEffect(selectedAudioMedia) {
        if (expandedController?.second is AudioController && selectedAudioMedia != null) {
            expandedController = selectedAudioMedia to PlaybackManager.audio
        }
    }
    LaunchedEffect(selectedVideoMedia) {
        if (expandedController?.second is VideoController && selectedVideoMedia != null) {
            expandedController = selectedVideoMedia to PlaybackManager.video
        }
    }

    if (hasPermission) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    Column {
                        if (audioState.currentMedia != null) MiniPlayer(audioState, audioController) { expandedController = audioState.currentMedia!! to audioController }
                        if (videoState.currentMedia != null) MiniPlayer(videoState, videoController) { expandedController = videoState.currentMedia!! to videoController }
                        NavigationBar {
                            NavigationBarItem(selected = currentTab == 0, onClick = { currentTab = 0 }, icon = { Icon(Icons.Default.MusicNote, null) }, label = { Text("Audio") })
                            NavigationBarItem(selected = currentTab == 1, onClick = { currentTab = 1 }, icon = { Icon(Icons.Default.Videocam, null) }, label = { Text("Video") })
                        }
                    }
                },
                topBar = {
                    TopAppBar(
                        title = { Text("Playback Library") },
                        actions = { 
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (currentTab == 0) {
                        MediaList(audioList, audioController) {
                            val index = audioList.indexOf(it)
                            PlaybackLogger.log("MainActivity", "USER CLICKED AUDIO LIST AT INDEX $index")
                            audioController.prepare(audioList, index)
                        }
                    } else {
                        MediaList(videoList, videoController) {
                            val index = videoList.indexOf(it)
                            videoController.prepare(videoList, index)
                            expandedController = it to videoController
                        }
                    }
                }
            }
            expandedController?.let { (media, controller) ->
                val state by controller.state.collectAsState()
                FullPlayer(state, controller, onDismiss = { expandedController = null })
            }

            FloatingActionButton(
                onClick = { showLogs = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 140.dp, end = 16.dp),
                containerColor = MaterialTheme.colorScheme.tertiary
            ) {
                Icon(Icons.Default.BugReport, null)
            }

            if (showLogs) {
                DiagnosticOverlay(
                    audioController = audioController,
                    videoController = videoController,
                    onClose = { showLogs = false }
                )
            }

            if (showSettings) {
                SettingsBottomSheet(
                    audioController = audioController,
                    videoController = videoController,
                    onDismiss = { showSettings = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    audioController: AudioController,
    videoController: VideoController,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp)) {
            Text("Library Settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            // --- Focus Section ---
            Text("Audio Focus Interruption Policy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            FocusIgnoreRow("Audio Focus (Ignore Loss)", audioController.ignoreAudioFocusLoss) { audioController.ignoreAudioFocusLoss = it }
            FocusIgnoreRow("Video Focus (Ignore Loss)", videoController.ignoreAudioFocusLoss) { videoController.ignoreAudioFocusLoss = it }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Crossfade Section ---
            var crossfadeSeconds by remember { mutableFloatStateOf(PlaybackManager.crossfadeDurationMs / 1000f) }
            Text("Crossfade Duration: ${crossfadeSeconds.toInt()}s", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Slider(
                value = crossfadeSeconds,
                onValueChange = { 
                    crossfadeSeconds = it
                    PlaybackManager.crossfadeDurationMs = (it * 1000).toLong()
                },
                valueRange = 0f..20f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- Lifecycle Policies ---
            Text("Lifecycle Policies (Swipe to Dismiss)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            PolicySettingsRow("Audio", PlaybackManager.audioTaskRemovedPolicy) { PlaybackManager.audioTaskRemovedPolicy = it }
            PolicySettingsRow("Video", PlaybackManager.videoTaskRemovedPolicy) { PlaybackManager.videoTaskRemovedPolicy = it }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Isolated Players ---
            Text("Isolated Debug Players", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = { 
                    onDismiss()
                    context.startActivity(Intent(context, PureMpvActivity::class.java)) 
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("Launch Pure MPV Player")
            }
        }
    }
}

@Composable
fun FocusIgnoreRow(label: String, current: Boolean, onToggle: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(current) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = {
            checked = it
            onToggle(it)
        })
    }
}

@Composable
fun PolicySettingsRow(label: String, current: TaskRemovedPolicy, onToggle: (TaskRemovedPolicy) -> Unit) {
    var selected by remember { mutableStateOf(current) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Row {
            FilterChip(
                selected = selected == TaskRemovedPolicy.KEEP_PLAYING,
                onClick = { selected = TaskRemovedPolicy.KEEP_PLAYING; onToggle(selected) },
                label = { Text("Keep", style = MaterialTheme.typography.labelSmall) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = selected == TaskRemovedPolicy.RELEASE,
                onClick = { selected = TaskRemovedPolicy.RELEASE; onToggle(selected) },
                label = { Text("Release", style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}
@Composable
fun MediaList(list: List<PlayableMedia>, controller: PlaybackAuthority, onClick: (PlayableMedia) -> Unit) {

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { media ->
            SwipeableItemRow(
                media = media,
                onPlayNext = { controller.playNext(media) },
                onAddToEnd = { controller.enqueue(media) },
                onClick = { onClick(media) }
            )
        }
    }
}

@Composable
fun SwipeableItemRow(
    media: PlayableMedia,
    onPlayNext: () -> Unit,
    onAddToEnd: () -> Unit,
    onClick: () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    val animatedOffset by animateFloatAsState(targetValue = offsetX)
    
    // We keep these for the UI color logic
    val threshold = 150f 
    val isArmedRight = offsetX > threshold
    val isArmedLeft = offsetX < -threshold

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .pointerInput(media.id) { // Tie to media.id so it resets correctly
                detectHorizontalDragGestures(
                    onDragEnd = {
                        // LIVE CHECK: Always check the latest offsetX here
                        if (offsetX > threshold) {
                            onPlayNext()
                        } else if (offsetX < -threshold) {
                            onAddToEnd()
                        }
                        offsetX = 0f 
                    },
                    onDragCancel = { offsetX = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        offsetX += dragAmount
                        change.consume()
                    }
                )
            }
    ) {
        // Background Actions
        val bgColor = when {
            isArmedRight -> Color.Green.copy(alpha = 0.6f)
            isArmedLeft -> Color.Blue.copy(alpha = 0.6f)
            else -> Color.Transparent
        }
        
        Box(modifier = Modifier.fillMaxSize().background(bgColor).padding(horizontal = 24.dp)) {
            if (isArmedRight) {
                Row(modifier = Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlaylistAddCheck, null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Play Next", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (isArmedLeft) {
                Row(modifier = Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                    Text("Add to End", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.PlaylistAdd, null, tint = Color.White)
                }
            }
        }

        // Foreground Content
        ListItem(
            headlineContent = { Text(media.title) },
            supportingContent = { Text(media.subtitle ?: "Unknown") },
            leadingContent = { AsyncImage(model = media.artworkUri, contentDescription = null, modifier = Modifier.size(50.dp), contentScale = ContentScale.Crop) },
            modifier = Modifier
                .offset { IntOffset(animatedOffset.toInt(), 0) }
                .background(MaterialTheme.colorScheme.surface)
                .clickable { onClick() }
        )
    }
}

@Composable
fun MiniPlayer(state: PlaybackState, controller: PlaybackAuthority, onExpand: () -> Unit) {
    val media = state.currentMedia ?: return

    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().height(72.dp).clickable { onExpand() }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            AsyncImage(model = media.artworkUri, contentDescription = null, modifier = Modifier.size(48.dp), contentScale = ContentScale.Crop)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(media.title, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                Text(media.subtitle ?: "", maxLines = 1, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            IconButton(onClick = { 
                controller.getMedia3Player()?.seekToPreviousMediaItem()
            }) {
                Icon(Icons.Default.SkipPrevious, null)
            }
            IconButton(onClick = { 
                controller.togglePlayPause()
            }) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
            }
            IconButton(onClick = { 
                controller.getMedia3Player()?.seekToNextMediaItem()
            }) {
                Icon(Icons.Default.SkipNext, null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayer(state: PlaybackState, controller: PlaybackAuthority, onDismiss: () -> Unit) {
    val media = state.currentMedia ?: return
    val isMpvActive = state.isMpvActive
    val isPlaying = state.isPlaying
    
    // Simple logic: Is the current engine NOT the default engine?
    val isToggled = isMpvActive != (PlaybackManager.defaultEngineType == EngineType.MPV)
    
    val altLabel = PlaybackManager.getAlternativeEngineName()
    val context = LocalContext.current

    val currentPos = state.currentPositionMs
    val duration = state.durationMs

    BackHandler { onDismiss() }
    var showQueue by remember { mutableStateOf(false) }
    
    val subtitlePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            controller.setExternalSubtitle(uri.toString())
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.KeyboardArrowDown, null) }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showQueue = !showQueue }) {
                            Icon(if (showQueue) Icons.Default.Close else Icons.Default.QueueMusic, null)
                        }
                        
                        Text(altLabel, style = MaterialTheme.typography.labelMedium)
                        Switch(checked = isToggled, onCheckedChange = { isChecked ->
                            // Use the new polymorphic toggle method
                            controller.toggleAlternativeEngine(isChecked)
                        }, modifier = Modifier.scale(0.8f))
                    }
                }
            
                Spacer(modifier = Modifier.height(16.dp))

                if (media.isVideo) {
                    Box(modifier = Modifier.fillMaxWidth().height(250.dp).background(Color.Black)) {
                        key(isMpvActive) {
                            AndroidView(
                                factory = { ctx -> 
                                    if (isMpvActive) {
                                        android.view.SurfaceView(ctx)
                                    } else {
                                        PlayerView(ctx).apply {
                                            useController = true 
                                            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                                        }
                                    }
                                },
                                update = { view ->
                                    if (controller is VideoController) {
                                        controller.setVideoView(view)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                } else {
                    AsyncImage(model = media.artworkUri, contentDescription = null, modifier = Modifier.size(250.dp).padding(16.dp), contentScale = ContentScale.Fit)
                }
                
                Text(media.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 24.dp))
                Text(media.subtitle ?: "Unknown Artist", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

                // --- Pre-Amp Gain Section ---
                var preAmpDb by remember { mutableStateOf(PlaybackManager.preAmpGainDb.toFloat()) }
                Spacer(modifier = Modifier.height(16.dp))
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Global Pre-Amp: ${String.format("%.1f", preAmpDb)} dB", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { 
                            preAmpDb = 0f
                            PlaybackManager.preAmpGainDb = 0.0
                            controller.refreshReplayGain()
                        }) { Text("Reset") }
                    }
                    Slider(
                        value = preAmpDb,
                        onValueChange = { 
                            preAmpDb = it
                            PlaybackManager.preAmpGainDb = it.toDouble()
                            controller.refreshReplayGain()
                        },
                        valueRange = -15f..15f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // -------------------------------

                if (media.isVideo && controller is VideoController) {
                    var isAutoplayEnabled by remember { mutableStateOf(true) }
                    var localDecoder by remember { mutableStateOf(DecoderPolicy.HW_PLUS) }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(modifier = Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Autoplay Next", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = isAutoplayEnabled,
                            onCheckedChange = { 
                                isAutoplayEnabled = it
                                controller.setAutoplay(it)
                            }
                        )
                    }

                    if (isMpvActive) {
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text("Decoder Strategy:", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DecoderPolicy.entries.forEach { policy ->
                                FilterChip(
                                    selected = localDecoder == policy,
                                    onClick = { 
                                        localDecoder = policy
                                        controller.setDecoderPolicy(policy)
                                    },
                                    label = { Text(policy.name.replace("_", "+"), style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Slider(
                    value = if (duration > 0) currentPos.toFloat() / duration.toFloat() else 0f,
                    onValueChange = { 
                        controller.seekTo((it * duration).toLong())
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                )
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(currentPos), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- Manual Volume Slider ---
                var manualVolume by remember { mutableStateOf(controller.getManualVolume()) }
                Row(modifier = Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(20.dp), tint = Color.Gray)
                    Slider(
                        value = manualVolume,
                        onValueChange = { 
                            manualVolume = it
                            controller.setVolume(it)
                        },
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp)
                    )
                }
                // -----------------------------

                // --- Playback Controls ---
                val isFavorite by GlobalTestFavorite.collectAsState()
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    // Favorite Toggle
                    IconButton(onClick = { GlobalTestFavorite.value = !GlobalTestFavorite.value }) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, 
                            null, 
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }

                    // Shuffle Toggle
                    IconButton(onClick = { controller.toggleShuffle() }) {
                        Icon(
                            Icons.Default.Shuffle, 
                            null, 
                            tint = if (state.isShuffleModeEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }

                    // Repeat Toggle
                    IconButton(onClick = { controller.toggleRepeatMode() }) {
                        val icon = when (state.repeatMode) {
                            RepeatMode.OFF -> Icons.Default.Repeat
                            RepeatMode.ALL -> Icons.Default.Repeat
                            RepeatMode.ONE -> Icons.Default.RepeatOne
                        }
                        Icon(
                            icon, 
                            null, 
                            tint = if (state.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }

                    // Speed/Pitch Toggle
                    var showSpeedDialog by remember { mutableStateOf(false) }
                    IconButton(onClick = { showSpeedDialog = true }) {
                        Icon(Icons.Default.Speed, null, tint = if (state.playbackSpeed != 1.0f || state.playbackPitch != 1.0f) MaterialTheme.colorScheme.primary else Color.Gray)
                    }

                    if (showSpeedDialog) {
                        AlertDialog(
                            onDismissRequest = { showSpeedDialog = false },
                            confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("Done") } },
                            title = { Text("Playback Speed & Pitch") },
                            text = {
                                Column {
                                    Text("Speed: ${"%.2f".format(state.playbackSpeed)}x", style = MaterialTheme.typography.labelMedium)
                                    Slider(
                                        value = state.playbackSpeed,
                                        onValueChange = { controller.setPlaybackSpeed(it) },
                                        valueRange = 0.5f..2.0f
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Pitch: ${"%.2f".format(state.playbackPitch)}x", style = MaterialTheme.typography.labelMedium)
                                    Slider(
                                        value = state.playbackPitch,
                                        onValueChange = { controller.setPlaybackPitch(it) },
                                        valueRange = 0.5f..2.0f
                                    )
                                    TextButton(onClick = { 
                                        controller.setPlaybackSpeed(1.0f)
                                        controller.setPlaybackPitch(1.0f)
                                    }) { Text("Reset to Normal") }
                                }
                            }
                        )
                    }

                    // Sleep Timer Toggle
                    var showSleepDialog by remember { mutableStateOf(false) }
                    IconButton(onClick = { showSleepDialog = true }) {
                        Icon(
                            Icons.Default.Bedtime, 
                            null, 
                            tint = if (state.sleepTimerState !is SleepTimerState.Inactive) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }

                    if (showSleepDialog) {
                        AlertDialog(
                            onDismissRequest = { showSleepDialog = false },
                            confirmButton = { TextButton(onClick = { showSleepDialog = false }) { Text("Close") } },
                            title = { Text("Sleep Timer") },
                            text = {
                                Column {
                                    val timerState = state.sleepTimerState
                                    if (timerState is SleepTimerState.TimeBased) {
                                        val minutes = timerState.remainingMs / 1000 / 60
                                        val seconds = (timerState.remainingMs / 1000) % 60
                                        Text("Active: ${"%02d:%02d".format(minutes, seconds)} remaining", color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.height(16.dp))
                                    } else if (timerState is SleepTimerState.EndOfTrack) {
                                        Text("Active: Stopping after current track", color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }

                                    ListItem(
                                        headlineContent = { Text("1 Minute (Test Fade)") },
                                        modifier = Modifier.clickable { controller.startSleepTimer(1 * 60 * 1000L); showSleepDialog = false }
                                    )
                                    ListItem(
                                        headlineContent = { Text("2 Minutes") },
                                        modifier = Modifier.clickable { controller.startSleepTimer(2 * 60 * 1000L); showSleepDialog = false }
                                    )
                                    ListItem(
                                        headlineContent = { Text("5 Minutes") },
                                        modifier = Modifier.clickable { controller.startSleepTimer(5 * 60 * 1000L); showSleepDialog = false }
                                    )
                                    ListItem(
                                        headlineContent = { Text("15 Minutes") },
                                        modifier = Modifier.clickable { controller.startSleepTimer(15 * 60 * 1000L); showSleepDialog = false }
                                    )
                                    ListItem(
                                        headlineContent = { Text("30 Minutes") },
                                        modifier = Modifier.clickable { controller.startSleepTimer(30 * 60 * 1000L); showSleepDialog = false }
                                    )
                                    ListItem(
                                        headlineContent = { Text("60 Minutes") },
                                        modifier = Modifier.clickable { controller.startSleepTimer(60 * 60 * 1000L); showSleepDialog = false }
                                    )
                                    ListItem(
                                        headlineContent = { Text("End of Track") },
                                        modifier = Modifier.clickable { controller.startSleepTimerEndOfTrack(); showSleepDialog = false }
                                    )
                                    if (state.sleepTimerState !is SleepTimerState.Inactive) {
                                        HorizontalDivider()
                                        ListItem(
                                            headlineContent = { Text("Cancel Timer", color = Color.Red) },
                                            modifier = Modifier.clickable { controller.cancelSleepTimer(); showSleepDialog = false }
                                        )
                                    }
                                }
                            }
                        )
                    }

                    if (media.isVideo) {
                        var showSubtitleDialog by remember { mutableStateOf(false) }
                        IconButton(onClick = { showSubtitleDialog = true }) {
                            Icon(
                                Icons.Default.ClosedCaption,
                                null,
                                tint = if (state.availableSubtitles.any { it.isSelected } || media.externalSubtitleUri != null) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }

                        if (showSubtitleDialog) {
                            AlertDialog(
                                onDismissRequest = { showSubtitleDialog = false },
                                confirmButton = { TextButton(onClick = { showSubtitleDialog = false }) { Text("Close") } },
                                title = { Text("Subtitles") },
                                text = {
                                    LazyColumn {
                                        item {
                                            ListItem(
                                                headlineContent = { Text("Load Custom Subtitle (.srt/.vtt)", color = MaterialTheme.colorScheme.primary) },
                                                modifier = Modifier.clickable { 
                                                    subtitlePickerLauncher.launch("*/*")
                                                    showSubtitleDialog = false
                                                }
                                            )
                                            HorizontalDivider()
                                            
                                            ListItem(
                                                headlineContent = { Text("Disable Subtitles") },
                                                modifier = Modifier.clickable { 
                                                    controller.selectSubtitleTrack(null)
                                                    showSubtitleDialog = false
                                                }
                                            )
                                            HorizontalDivider()
                                        }
                                        items(state.availableSubtitles) { track ->
                                            val displayName = track.label ?: track.language ?: "Track ${track.id}"
                                            ListItem(
                                                headlineContent = { Text(displayName, color = if (track.isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                                                modifier = Modifier.clickable { 
                                                    controller.selectSubtitleTrack(track.id)
                                                    showSubtitleDialog = false
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    if (state.availableAudioTracks.size > 1) {
                        var showAudioDialog by remember { mutableStateOf(false) }
                        IconButton(onClick = { showAudioDialog = true }) {
                            Icon(
                                Icons.Default.Audiotrack,
                                null,
                                tint = Color.Gray
                            )
                        }

                        if (showAudioDialog) {
                            AlertDialog(
                                onDismissRequest = { showAudioDialog = false },
                                confirmButton = { TextButton(onClick = { showAudioDialog = false }) { Text("Close") } },
                                title = { Text("Audio Tracks") },
                                text = {
                                    LazyColumn {
                                        items(state.availableAudioTracks) { track ->
                                            val displayName = track.label ?: track.language ?: "Track ${track.id}"
                                            ListItem(
                                                headlineContent = { Text(displayName, color = if (track.isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                                                modifier = Modifier.clickable { 
                                                    controller.selectAudioTrack(track.id)
                                                    showAudioDialog = false
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                    if (media.isVideo) {
                        IconButton(onClick = { 
                            val nextMode = when (state.scaleMode) {
                                aman.playbackengine.enginecore.ScaleMode.FIT -> aman.playbackengine.enginecore.ScaleMode.FILL
                                aman.playbackengine.enginecore.ScaleMode.FILL -> aman.playbackengine.enginecore.ScaleMode.STRETCH
                                aman.playbackengine.enginecore.ScaleMode.STRETCH -> aman.playbackengine.enginecore.ScaleMode.FIT
                            }
                            controller.setScaleMode(nextMode)
                        }) {
                            Icon(
                                androidx.compose.material.icons.Icons.Default.AspectRatio,
                                contentDescription = "Toggle Scale Mode",
                                tint = if (state.scaleMode != aman.playbackengine.enginecore.ScaleMode.FIT) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }
                    }
                    
                    var showEqDialog by remember { mutableStateOf(false) }
                    IconButton(onClick = { showEqDialog = true }) {
                        val eqEnabled by PlaybackManager.equalizer.enabled.collectAsState()
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Equalizer",
                            tint = if (eqEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }

                    if (showEqDialog) {
                        AlertDialog(
                            onDismissRequest = { showEqDialog = false },
                            confirmButton = { TextButton(onClick = { showEqDialog = false }) { Text("Close") } },
                            title = { Text("Equalizer") },
                            text = {
                                val eqEnabled by PlaybackManager.equalizer.enabled.collectAsState()
                                val bands by PlaybackManager.equalizer.bands.collectAsState()
                                
                                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Enable Equalizer", modifier = Modifier.weight(1f))
                                        Switch(checked = eqEnabled, onCheckedChange = { PlaybackManager.equalizer.setEnabled(it) })
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    
                                    bands.forEach { band ->
                                        val hz = if (band.centerFreqHz >= 1000000) {
                                            "${band.centerFreqHz / 1000000} kHz"
                                        } else {
                                            "${band.centerFreqHz / 1000} Hz"
                                        }
                                        val db = band.currentLevel / 100f
                                        
                                        Text("$hz: ${"%.1f".format(db)} dB", fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp))
                                        Slider(
                                            value = band.currentLevel.toFloat(),
                                            onValueChange = { PlaybackManager.equalizer.setBandLevel(band.id, it.toInt().toShort()) },
                                            valueRange = band.minLevel.toFloat()..band.maxLevel.toFloat(),
                                            enabled = eqEnabled
                                        )
                                    }
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { 
                        controller.getMedia3Player()?.seekToPreviousMediaItem()
                    }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Default.SkipPrevious, null, modifier = Modifier.size(48.dp))
                    }
                    
                    Spacer(modifier = Modifier.width(24.dp))

                    FloatingActionButton(onClick = { 
                        controller.togglePlayPause()
                    }, modifier = Modifier.size(80.dp), shape = androidx.compose.foundation.shape.CircleShape) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(48.dp))
                    }

                    Spacer(modifier = Modifier.width(24.dp))

                    IconButton(onClick = { 
                        controller.getMedia3Player()?.seekToNextMediaItem()
                    }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(48.dp))
                    }
                }
            }
            
            AnimatedVisibility(
                visible = showQueue,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                QueueOverlay(state, controller)
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

@Composable
fun QueueOverlay(state: PlaybackState, controller: PlaybackAuthority) {
    val queue = state.queue
    val currentMedia = state.currentMedia
    
    // --- Deferred Commit Logic ---
    var tempQueue by remember { mutableStateOf(queue) }
    var draggingItemUid by remember { mutableStateOf<String?>(null) }
    var initialDragIndex by remember { mutableIntStateOf(-1) }
    
    LaunchedEffect(queue) {
        if (draggingItemUid == null) {
            tempQueue = queue
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (draggingItemUid == null) {
            draggingItemUid = tempQueue.getOrNull(from.index)?.uid
            initialDragIndex = from.index
        }
        
        // UI ONLY: Perform visual swap instantly
        tempQueue = tempQueue.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize().padding(top = 64.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Up Next", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { controller.toggleShuffle() }) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (state.isShuffleModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                    if (state.isShuffleModeEnabled) {
                        IconButton(onClick = { controller.reshuffle() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reshuffle",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

            }
            
            HorizontalDivider()

            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                itemsIndexed(tempQueue, key = { _, item -> item.uid }) { index, item ->
                    ReorderableItem(reorderableState, key = item.uid) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                        val isCurrent = item.uid == currentMedia?.uid
                        
                        Surface(
                            tonalElevation = elevation,
                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = null,
                                    modifier = Modifier.draggableHandle(
                                        onDragStopped = {
                                            // FINAL COMMIT: Find where our item ended up
                                            val uid = draggingItemUid
                                            if (uid != null && initialDragIndex != -1) {
                                                val finalIndex = tempQueue.indexOfFirst { it.uid == uid }
                                                if (finalIndex != -1 && finalIndex != initialDragIndex) {
                                                    controller.move(initialDragIndex, finalIndex)
                                                }
                                            }
                                            draggingItemUid = null
                                            initialDragIndex = -1
                                        }
                                    ).padding(end = 16.dp),
                                    tint = Color.Gray
                                )
                                
                                Column(modifier = Modifier.weight(1f).clickable { controller.skipToIndex(index) }) {
                                    Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified)
                                    Text(item.subtitle ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }

                                IconButton(onClick = { controller.remove(index) }) {
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
