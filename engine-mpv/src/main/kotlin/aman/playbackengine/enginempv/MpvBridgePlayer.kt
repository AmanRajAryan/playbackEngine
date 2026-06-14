package aman.playbackengine.enginempv

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import aman.playbackengine.enginecore.PlaybackLogger
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Media3 Bridge for MPV.
 * Optimized with the high-performance 'Snapshot' strategy.
 */
@UnstableApi
class MpvBridgePlayer(
    looper: Looper,
    private val core: MpvCore
) : SimpleBasePlayer(looper) {

    fun invalidate() { invalidateState() }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var lastEmittedState: MpvState? = null

    init {
        core.state.onEach { newState ->
            val oldState = lastEmittedState
            
            // Only invalidate when fundamental properties change.
            val stateChanged = oldState == null ||
                oldState.isPaused != newState.isPaused ||
                oldState.isIdle != newState.isIdle ||
                oldState.isBuffering != newState.isBuffering ||
                oldState.isEnded != newState.isEnded ||
                oldState.currentIndex != newState.currentIndex ||
                oldState.durationMs != newState.durationMs ||
                oldState.speed != newState.speed

            if (stateChanged) {
                lastEmittedState = newState
                invalidateState()
            }
        }.launchIn(scope)
    }

    override fun getState(): State {
        val state = core.state.value

        val playerState = if (state.playlist.isEmpty()) {
            Player.STATE_IDLE
        } else if (state.isEnded) {
            Player.STATE_ENDED
        } else if (state.isBuffering || state.isIdle) {
            // Note: Media3 uses STATE_BUFFERING to keep the service in foreground 
            // when playWhenReady is true but content isn't ready.
            Player.STATE_BUFFERING
        } else {
            Player.STATE_READY
        }

        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME
            )
            .build()

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(playerState)
            .setPlayWhenReady(!state.isPaused, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(state.speed.toFloat()))
            .setContentPositionMs { core.state.value.positionMs }

        if (state.playlist.isNotEmpty()) {
            val playlistData = state.playlist.mapIndexed { index, item ->
                val durationMs = if (index == state.currentIndex) {
                    state.durationMs.takeIf { it > 0 } ?: C.TIME_UNSET
                } else {
                    C.TIME_UNSET
                }
                
                // Compatibility Extra for older OEMs and Car units
                val updatedMetadata = item.mediaMetadata.buildUpon()
                    .setExtras(android.os.Bundle().apply { 
                        if (durationMs != C.TIME_UNSET) {
                            putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, durationMs) 
                        }
                    })
                    .build()
                
                MediaItemData.Builder(item.mediaId)
                    .setMediaItem(item.buildUpon().setMediaMetadata(updatedMetadata).build())
                    .setMediaMetadata(updatedMetadata)
                    .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000)
                    .build()
            }
            builder.setPlaylist(playlistData)
            builder.setCurrentMediaItemIndex(state.currentIndex)
        }

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) core.play() else core.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        val state = core.state.value
        if (mediaItemIndex != state.currentIndex) {
            if (mediaItemIndex > state.currentIndex) {
                core.skipToNext()
            } else {
                core.skipToPrevious()
            }
        } else {
            core.seekTo(positionMs)
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        // No-op: The controller owns the engine lifecycle.
        // MediaSession must not stop the engine (e.g. on notification dismiss).
        return Futures.immediateVoidFuture()
    }
}
