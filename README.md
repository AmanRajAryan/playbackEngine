# playbackEngine

A high-performance, modular Android media library providing a unified abstraction for sophisticated audio and video playback. Built with a "Relational Foundation," this library supports seamless engine swapping, industrial-strength persistence, and professional-grade volume normalization.

## 🚀 Key Features

*   **Multi-Engine Orchestration**: Seamlessly swap between **ExoPlayer** (Media3) and **libmpv** (FFmpeg) without interrupting the user experience.
*   **Zero-JSON Relational Persistence**: A fully structured SQLite backend with three-tiered saving (Position, Metadata, and Structural Sync) for maximum performance and disk longevity.
*   **Professional Audio Pipeline**: 
    *   **Linear Parity**: Mathematical normalization between engines (ExoPlayer's linear vs. MPV's cubic curves).
    *   **ReplayGain Support**: Built-in volume normalization with global pre-amp controls.
    *   **FadeCoordinator**: A deterministic, time-linked crossfade engine for audio transitions.
*   **Modular Architecture**: Logic is strictly segregated into specialized delegates for State, Focus, and Persistence, ensuring zero memory leaks and high testability.
*   **Resilient Design**: Atomic state transitions, "Ghost State" restoration, and robust lifecycle management (including manual-release guards).

## 📦 Project Structure

The library is divided into four focused modules:

*   `:engine-core`: The heart of the library. Contains universal interfaces, orchestration logic, and the relational persistence layer.
*   `:engine-exoplayer`: implementation of `PlaybackEngine` using Google's Media3 ExoPlayer.
*   `:engine-mpv`: High-performance engine wrapper for `libmpv`, enabling advanced FFmpeg filtering and format support.
*   `:app`: A reference implementation using Jetpack Compose, featuring a diagnostic overlay for real-time monitoring.

## 🛠 Installation

Add the following to your `build.gradle.kts`:

```kotlin
dependencies {
    // Core interfaces and orchestration
    implementation("io.github.amanrajaryan:playback-engine-core:1.0.0")

    // Engine implementations (pick one or both)
    implementation("io.github.amanrajaryan:playback-engine-exoplayer:1.0.0")
    implementation("io.github.amanrajaryan:playback-engine-mpv:1.0.0")
}
```

> **Note on MPV**: Due to size and licensing, the native `mpv-android-lib` AAR must be provided manually. Download the binary from our [Releases](https://github.com/AmanRajAryan/playbackEngine/releases) page and place it in your project's `libs` folder.

## 📖 Basic Usage

### 1. Initialization
The library uses an auto-initialization strategy via an internal `ContentProvider`, so no manual setup in `onCreate` is required.

### 2. Controlling Playback
Access the controllers via the `PlaybackManager`:

```kotlin
val audio = PlaybackManager.audio

// Load a list and play
audio.prepare(mediaList, startIndex = 0)

// Standard controls
audio.play()
audio.pause()
audio.seekTo(positionMs)
```

### 3. Global Configuration
Configure library-wide policies directly:

```kotlin
// Change crossfade duration
PlaybackManager.crossfadeDurationMs = 5000L 

// Define behavior when app is swiped away
PlaybackManager.audioTaskRemovedPolicy = TaskRemovedPolicy.KEEP_PLAYING

// Enable global audio focus bypass
audio.ignoreAudioFocusLoss = true
```

## 📊 Diagnostics
The library includes a `DiagnosticOverlay` (Compose) for real-time monitoring of:
*   Memory usage (JVM & Native Heap).
*   Active engine state and buffer status.
*   Crossfade progress and volume multipliers.

## 📄 License
Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

---
