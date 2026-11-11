package io.putdotio.android.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player as Media3Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStallWatchTest {
    @Test
    fun aLongBufferShowsBufferingAfterTenSecondsAndStalledAfterThirty() = runTest {
        val player = StallPlayer()
        player.play()
        val watch = watch(player)
        player.buffer()
        runCurrent()
        assertEquals(PlaybackStall.Waiting, watch.stall.value)
        advanceTimeBy(9_999L)
        runCurrent()
        assertEquals(PlaybackStall.Waiting, watch.stall.value)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(PlaybackStall.Buffering, watch.stall.value)
        advanceTimeBy(19_999L)
        runCurrent()
        assertEquals(PlaybackStall.Buffering, watch.stall.value)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(PlaybackStall.Stalled, watch.stall.value)
        watch.close()
        player.release()
    }

    @Test
    fun recoveringOrPausingClearsTheStallAndTheNextBufferStartsAgain() = runTest {
        val player = StallPlayer()
        player.play()
        val watch = watch(player)
        player.buffer()
        advanceTimeBy(20_000L)
        runCurrent()
        player.ready()
        runCurrent()
        assertEquals(PlaybackStall.None, watch.stall.value)
        player.buffer()
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(PlaybackStall.Buffering, watch.stall.value)
        player.pause()
        runCurrent()
        assertEquals(PlaybackStall.None, watch.stall.value)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(PlaybackStall.None, watch.stall.value)
        watch.close()
        player.release()
    }

    @Test
    fun retryPreparesAgainAndRestartsTheCount() = runTest {
        val player = StallPlayer()
        player.play()
        val watch = watch(player)
        player.buffer()
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(PlaybackStall.Stalled, watch.stall.value)
        watch.retry()
        runCurrent()
        assertEquals(1, player.prepares)
        assertEquals(PlaybackStall.Waiting, watch.stall.value)
        advanceTimeBy(29_999L)
        runCurrent()
        assertEquals(PlaybackStall.Buffering, watch.stall.value)
        player.ready()
        runCurrent()
        assertEquals(PlaybackStall.None, watch.stall.value)
        watch.close()
        player.release()
    }

    @Test
    fun aPlayRequestWhileStalledKeepsTheStalledState() = runTest {
        val player = StallPlayer()
        player.play()
        val watch = watch(player)
        player.buffer()
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(PlaybackStall.Stalled, watch.stall.value)
        player.playAgain()
        runCurrent()
        assertEquals(PlaybackStall.Stalled, watch.stall.value)
        watch.close()
        player.release()
    }

    private fun TestScope.watch(player: StallPlayer): PlaybackStallWatch = PlaybackStallWatch(player, backgroundScope)
}

private const val STREAM_URL = "https://example.test/stream.m3u8"

@UnstableApi
private class StallPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    var prepares = 0
        private set

    private var state = State.Builder()
        .setAvailableCommands(Media3Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(listOf(MediaItemData.Builder("item").setMediaItem(MediaItem.fromUri(STREAM_URL)).build()))
        .setCurrentMediaItemIndex(0)
        .setPlaybackState(Media3Player.STATE_READY)
        .build()

    override fun getState(): State = state

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    fun buffer() {
        state = state.buildUpon().setPlaybackState(Media3Player.STATE_BUFFERING).build()
        invalidateState()
    }

    fun playAgain() {
        state = state.buildUpon()
            .setPlayWhenReady(true, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .build()
        invalidateState()
    }

    fun ready() {
        state = state.buildUpon().setPlaybackState(Media3Player.STATE_READY).build()
        invalidateState()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        state = state.buildUpon()
            .setPlayWhenReady(playWhenReady, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        state = state.buildUpon().setPlaybackState(Media3Player.STATE_IDLE).build()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepares += 1
        state = state.buildUpon().setPlaybackState(Media3Player.STATE_BUFFERING).build()
        return Futures.immediateVoidFuture()
    }
}
