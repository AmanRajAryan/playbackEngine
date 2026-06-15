# PlaybackEngine

A powerful, drop-in, dual-engine audio and video playback library for Android. Built on top of AndroidX Media3, it provides a unified, highly-reactive interface for gapless playback, crossfading, background persistence, and seamless switching between **ExoPlayer** and native **MPV** hardware decoding.

## Core Features

- **Unified Interface:** Common API for controlling both audio and video playback.
- **Modular Engines:** Optional implementations for ExoPlayer and MPV. Register only the engines required by your application to minimize dependency footprint.
- **Audio Transitions:** Built-in support for volume crossfading and gapless playback.
- **State Management:** Reactive state observation exposed via Kotlin `StateFlow`.
- **Queue Persistence:** SQLite-backed persistence for playlist structures and playback positions across process recreation.
- **Media3 Integration:** Implements Media3 interfaces to natively support MediaSession, system lockscreen controls, and Bluetooth routing.

## Installation



```gradle
dependencies {
    // Core interfaces and orchestration
    implementation("io.github.amanrajaryan:playback-engine-core:1.0.3")

    // Then pick your preferred engine (or both)
    implementation("io.github.amanrajaryan:playback-engine-exoplayer:1.0.3")
    implementation("io.github.amanrajaryan:playback-engine-mpv:1.0.3")
}
```

## Getting Started

### 1. Initialization and Plugging Engines

PlaybackEngine's core does not force any specific player. You must register the engine providers you wish to use. This makes engines completely optional. 

Typically, you do this once in your `Application` class or main activity:

```kotlin
// Register ExoPlayer for Audio/Video
PlaybackManager.registerProvider(ExoEngineProvider())

// Register MPV for alternative Video/Audio decoding
PlaybackManager.registerProvider(MpvEngineProvider())

// Optional: You can set MPV as the default engine if both are registered
PlaybackManager.defaultEngineType = EngineType.MPV
```

### 2. Playing Audio

Interact with the `PlaybackManager.audio` controller to manage audio playback. It handles background services automatically.

```kotlin
val audioController = PlaybackManager.audio

// Create media items
val media = PlayableMedia(
    id = "1",
    uri = Uri.parse("https://example.com/audio.mp3"),
    title = "Awesome Song",
    subtitle = "Great Artist",
    artworkUri = Uri.parse("https://example.com/cover.jpg"),
    trackGain = -4.5, // Optional: Provide parsed ReplayGain tags
    albumGain = -3.2,
    peak = 0.98
)

// Start playback
audioController.prepare(listOf(media), startIndex = 0)
```

### 3. Playing Video

The video controller works exactly the same as the audio controller. To display video, attach an Android `SurfaceView` or Media3 `PlayerView`:

```kotlin
val videoController = PlaybackManager.video

// Attach your view (e.g., from a Compose AndroidView or XML)
videoController.setVideoView(mySurfaceView)

// Create a video media item
val videoMedia = PlayableMedia(
    id = "vid_1",
    uri = Uri.parse("https://example.com/video.mp4"),
    title = "Awesome Video",
    isVideo = true
)

// Start playback
videoController.prepare(listOf(videoMedia), startIndex = 0)
```

### 4. Queue Management

The `PlaybackManager` exposes simple methods to control the queue dynamically:

```kotlin
// Add to the end of the queue
audioController.enqueue(newMedia)

// Play next (insert immediately after current item)
audioController.playNext(newMedia)

// Play a specific item in the queue
audioController.skipToIndex(index = 1)

// Move an item
audioController.moveQueueItem(fromIndex = 0, toIndex = 3)

// Remove an item
audioController.removeQueueItem(index = 2)

// Toggle whether the queue automatically advances to the next item
videoController.setAutoplay(false)

// Toggle Shuffle or Repeat Mode
audioController.toggleShuffle()
audioController.toggleRepeatMode()

// Manual Volume Control (0.0 to 1.0)
audioController.setVolume(0.8f)

// Clear the queue
audioController.clearQueue()
```

### 5. Observing State (Jetpack Compose)

PlaybackEngine heavily utilizes `StateFlow`. Observing state in Compose is seamless:

```kotlin
val audioState by PlaybackManager.audio.state.collectAsState()

if (audioState.isPlaying) {
    Text("Currently playing: ${audioState.currentMedia?.title}")
    Text("Progress: ${audioState.currentPositionMs} / ${audioState.durationMs}")
}
```

### 6. Media Configuration (Subtitles, Audio Tracks, Scale)

PlaybackEngine exposes a unified API to handle media configuration across both ExoPlayer and MPV:

**Subtitles:**
```kotlin
val videoState by videoController.state.collectAsState()

// Load an external subtitle file (.srt, .vtt, etc.)
videoController.setExternalSubtitle("file:///path/to/subtitle.srt")

// Select an embedded or loaded subtitle track
videoController.selectSubtitleTrack(videoState.availableSubtitles.firstOrNull()?.id)

// Turn subtitles off
videoController.selectSubtitleTrack(null)
```

**Audio Tracks (Multi-language):**
```kotlin
// Select a specific audio track by ID
videoController.selectAudioTrack(videoState.availableAudioTracks[1].id)
```

**Video Scaling Modes:**
Configure how the video surface fits into its container (`FIT`, `FILL`, or `STRETCH`):
```kotlin
videoController.setScaleMode(ScaleMode.FILL)
```

### 7. Advanced Features

**Crossfading**
ExoPlayer audio supports automatic crossfading. Configure the duration:
```kotlin
PlaybackManager.crossfadeDurationMs = 5000L // 5 seconds
```

**Global Pre-Amp**
Boost audio levels using the software pre-amp:
```kotlin
PlaybackManager.preAmpGainDb = 5.0 // Boost by 5dB
PlaybackManager.audio.refreshReplayGain()
```

**Sleep Timer**
Automatically stop playback after a set time or at the end of the track:
```kotlin
// Stop after 30 minutes
PlaybackManager.audio.startSleepTimer(30 * 60 * 1000L)

// Stop at the end of the current track
PlaybackManager.audio.startSleepTimerEndOfTrack()
```

**Background Lifecycle (Task Removed)**
Control exactly how streams behave when the app is swiped from recents:
```kotlin
PlaybackManager.audioTaskRemovedPolicy = TaskRemovedPolicy.KEEP_PLAYING
PlaybackManager.videoTaskRemovedPolicy = TaskRemovedPolicy.RELEASE
```

**Video Decoder Policy**
Optimize battery life or compatibility by changing the hardware decoding strategy on the fly:
```kotlin
PlaybackManager.video.setDecoderPolicy(DecoderPolicy.HW_PLUS) // HW_PLUS, HW, or SW
```

**Playback Speed & Pitch**
Adjust tempo and pitch dynamically. These settings, along with shuffle and repeat modes, are instantly persisted to `SharedPreferences` and restored automatically:
```kotlin
PlaybackManager.audio.setPlaybackSpeed(1.5f) // 1.5x speed
PlaybackManager.audio.setPlaybackPitch(1.2f)
```

**Loudness Normalization (ReplayGain)**
PlaybackEngine automatically scales volume to prevent loud jumps between tracks. Simply parse the ReplayGain tags in your app, pass `trackGain`, `albumGain`, and `peak` into your `PlayableMedia` objects, and set the mode:
```kotlin
PlaybackManager.replayGainMode = ReplayGainMode.TRACK // or ALBUM / OFF
```

**Hardware Equalizer (DSP)**
PlaybackEngine provides a global hardware-accelerated Equalizer API that works across both engines automatically. It automatically falls back to a 5-band software equalizer if the device hardware denies DSP access.
```kotlin
// Enable or disable the Equalizer
PlaybackManager.equalizer.setEnabled(true)

// Observe available bands and their current levels
val bands by PlaybackManager.equalizer.bands.collectAsState()

// Update a band level (e.g., from a slider)
PlaybackManager.equalizer.setBandLevel(bandId = 0, level = 1500) // max boost
```

**MPV Grace Period**
To prevent the heavy cost of repeatedly destroying and recreating the native C MPV core, you can set a grace period for teardown:
```kotlin
PlaybackManager.mpvGracePeriodMs = 10000L // Keeps MPV alive for 10s after release
```

**UI Lifecycle & Notification Syncing**
To allow the background service to intelligently decide whether to show Audio or Video controls in the system notification, you should sync your app's visual state:
```kotlin
// In your Activity/Fragment onResume:
PlaybackManager.isAppInForeground.value = true
PlaybackManager.activeScreenRole.value = SessionRole.VIDEO // or AUDIO

// In onPause:
PlaybackManager.isAppInForeground.value = false
```

**Custom Notification Actions**
You can add your own custom buttons (like Favorite or Repeat) directly to the system notification and Android Auto! Because `PlaybackEngine` multiplexes Audio and Video, you define the buttons for each stream independently:
```kotlin
// 1. Build your custom button
val favoriteButton = CommandButton.Builder()
    .setDisplayName("Favorite")
    .setIconResId(R.drawable.ic_heart_empty) // Replace with your drawable
    .setSessionCommand(SessionCommand("ACTION_TOGGLE_FAVORITE", android.os.Bundle.EMPTY))
    .build()

// 2. Push it to the library
PlaybackManager.audioNotificationButtons.value = listOf(favoriteButton)

// 3. Listen for clicks anywhere in your app (e.g., your ViewModel)
lifecycleScope.launch {
    PlaybackManager.customCommandEvents.collect { command ->
        if (command.customAction == "ACTION_TOGGLE_FAVORITE") {
            // Handle the click and push a new button with the updated icon!
        }
    }
}
```

> ⚠️ **Best Practice - Static Action Strings:** When updating a button's state dynamically (e.g., swapping a hollow heart to a filled heart), **never change the `SessionCommand` action string itself**. Android's `MediaSession` permission gateway evaluates and grants allowed custom actions once during the initial controller connection. If you dynamically swap `"ACTION_FAVORITE_ADD"` to `"ACTION_FAVORITE_REMOVE"`, the system will silently drop the button because the new string was never explicitly granted!
> **Instead:** Always use a static, constant action string (like `"ACTION_TOGGLE_FAVORITE"`) and dynamically change the `.setIconResId()` or `.setDisplayName()` to reflect the new state.

*Note: The Android 13+ lockscreen restricts custom actions to a maximum of 5 compact slots total. Additionally, ensure you push your buttons early (e.g., in a custom `Application` class) so they survive background service restarts.*

---
*For a deeper dive into how PlaybackEngine works under the hood (State Coordinators, Persistence Tiers, and Dual-Engine Orchestration), check out [ARCHITECTURE.md](ARCHITECTURE.md).*
