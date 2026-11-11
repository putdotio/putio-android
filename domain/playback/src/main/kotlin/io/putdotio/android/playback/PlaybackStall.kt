package io.putdotio.android.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.Player
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val PLAYBACK_BUFFERING_NOTICE_MILLIS: Long = 10_000L
internal const val PLAYBACK_STALLED_MILLIS: Long = 30_000L

public enum class PlaybackStall { None, Waiting, Buffering, Stalled }

public class PlaybackStallWatch(
    private val player: Player,
    parentScope: CoroutineScope,
) : Closeable, RememberObserver {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val current = MutableStateFlow(PlaybackStall.None)
    private var timer: Job? = null
    private var closed = false

    public val stall: StateFlow<PlaybackStall> = current.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = update(restart = false)

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = update(restart = false)

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) update(restart = true)
        }
    }

    init {
        player.addListener(listener)
        update(restart = false)
    }

    public fun retry() {
        if (closed) return
        player.stop()
        player.prepare()
        update(restart = true)
    }

    override fun onRemembered(): Unit = Unit

    override fun onForgotten(): Unit = close()

    override fun onAbandoned(): Unit = close()

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        player.removeListener(listener)
    }

    private fun update(restart: Boolean) {
        if (closed) return
        if (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
            if (restart || current.value == PlaybackStall.None) startTimer()
        } else {
            timer?.cancel()
            timer = null
            current.value = PlaybackStall.None
        }
    }

    private fun startTimer() {
        timer?.cancel()
        current.value = PlaybackStall.Waiting
        timer = scope.launch {
            delay(PLAYBACK_BUFFERING_NOTICE_MILLIS)
            current.value = PlaybackStall.Buffering
            delay(PLAYBACK_STALLED_MILLIS - PLAYBACK_BUFFERING_NOTICE_MILLIS)
            current.value = PlaybackStall.Stalled
        }
    }
}

@Composable
public fun rememberPlaybackStallWatch(player: Player): PlaybackStallWatch {
    val scope = rememberCoroutineScope()
    return remember(player) { PlaybackStallWatch(player, scope) }
}
