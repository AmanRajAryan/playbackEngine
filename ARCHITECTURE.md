# PlaybackEngine Architecture

This document details the internal engineering of `PlaybackEngine`. It is intended for contributors and advanced users who want to understand how the library achieves gapless playback, crossfading, persistence, and engine-agnostic decoding.

## 1. The Orchestrator-Delegate Pattern (Eliminating God Objects)

Media playback logic is inherently complex (managing focus, media sessions, notifications, state, and the player itself). Shoving this all into a single class creates a rigid "God Object." 

`PlaybackEngine` solves this by decoupling responsibilities:
- **`PlaybackManager` (Singleton Orchestrator):** The global entry point. It holds references to `AudioController` and `VideoController` and manages module registration.
- **`BasePlaybackController`:** The backbone abstract class. It delegates specific functional domains to isolated helpers:
  - `FocusDelegate`: Manages Android AudioFocus and ducking.
  - `StateCoordinator`: Merges native engine state and internal queue state into a reactive `StateFlow`.
  - `PersistenceDelegate`: Interacts with the database layer.
  - `SessionJournal`: Keeps track of debug logs and events.
- **`PlaybackService` & Global `MediaSession`:** Instead of maintaining separate sessions, `PlaybackService` maintains a *single global* `MediaSession`. A `NotificationPriorityProvider` dictates which stream (Audio or Video) currently owns the session, swapping the underlying `Player` seamlessly to prevent notification flickering.
- **`FocusDelegate` & Mutual Pause:** `PlaybackManager` strictly enforces a mutual pause strategy (if video plays, audio pauses) unless explicitly configured otherwise. `FocusDelegate` handles OS-level interruptions, including gracefully ducking volume when a notification arrives.
- **`SessionJournal`:** A built-in rolling diagnostic log that captures the last 20 state transitions and actions. Used primarily to attach exact user-trajectories to uncaught crash reports (`CrashActivity`), making debugging production crashes trivial.
- **`PlaybackEngine` (Interface):** The strict contract that native engines (`ExoPlayer`, `MPV`) must implement. The controller never interacts directly with ExoPlayer or MPV; it only speaks to `PlaybackEngine`.

## 2. Modular Dual-Engine Strategy

The library ships with a modular core (`engine-core`) and optional plugins (`engine-exoplayer`, `engine-mpv`).

### `EngineProvider`
Consumers register engines via `EngineProvider`. This makes the heavy dependencies of Media3 or MPV entirely optional. If an app only needs ExoPlayer for audio, they don't need to bloat their APK with MPV native libraries.

### `CrossfadeExoEngine`
To achieve seamless DJ-style crossfading, this engine utilizes two overlapping `ExoPlayer` instances (`player1` and `player2`).
- As the primary track nears completion, the secondary player prepares the next track in the background.
- At the crossfade threshold, a volume ramp coroutine linearly fades out the primary player while fading in the secondary player.

### `MpvEngine` & Native Wrappers
MPV provides robust hardware decoding (`mediacodec`) and a highly configurable software pipeline.
- `MpvCore` wraps the native `libmpv` JNI calls and acts as a translation layer.
- **`MpvResourceManager` (Lease Contention):** Because `libmpv` is a heavy, single native context, `MpvResourceManager` coordinates a strict lease system. If the Video stream requests MPV while Audio is currently using it, the manager issues a `handleMpvRevocation()` call to Audio, forcing it to seamlessly yield and gracefully fall back to ExoPlayer!

## 3. SQLite Queue Persistence (3-Tier Strategy)

Surviving process death and background caching is handled automatically by `PlaybackDatabaseHelper` and `QueuePersister`.

We use a **3-Tier Persistence Strategy** to balance performance and reliability:
- **Tier 1 (Position - O(1)):** The current playback position (`positionMs`) is saved frequently to a lightweight `playback_state` table.
- **Tier 2 (Metadata - O(1)):** Current track UID and Shuffle states are saved when tracks change.
- **Tier 3 (Full Queue - O(N)):** The entire master list and shuffled lists are serialized and saved only when the queue structure is mutated (e.g., enqueue, remove, clear).

This ensures that UI thread performance is never impacted by heavy database writes during standard playback.

## 4. Reactive State & Concurrency

The entire library is built on Kotlin Coroutines and `StateFlow`.

- **`EngineState`:** A sealed class representing the raw engine status (`Idle`, `Playing`, `Paused`, `Buffering`, `Ended`).
- **`PlaybackState`:** A comprehensive data class exposing the current `PlayableMedia`, position, duration, volume, and sleep timer status.
- `StateCoordinator` listens to the native engine flow and the `MediaQueue` flow, combining them into the final `PlaybackState` exposed to the UI.

Because it relies on `StateFlow`, UI layers (like Jetpack Compose) can observe exact playback states globally without callbacks or listeners.
