package io.putdotio.android

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player as Media3Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class MobilePlayerPositionObserverTest {
    @Test
    fun briefBufferingDoesNotPostponePeriodicReportsIndefinitely() = runTest {
        val player = PositionPlayer()
        player.replace("first", 10_000L)
        player.play()
        val snapshots = mutableListOf<Pair<String, Long>>()
        val observer = MobilePlayerPositionObserver(player, backgroundScope) { lease, position ->
            snapshots += lease to position
        }
        runCurrent()
        repeat(3) { index ->
            advanceTimeBy(4_000L)
            player.buffer()
            runCurrent()
            advanceTimeBy(1_000L)
            player.ready()
            player.position(15_000L + index * 5_000L)
            runCurrent()
        }
        assertEquals(listOf("first" to 25_000L), snapshots)
        observer.close()
        player.release()
    }

    @Test
    fun samplesEveryFifteenSecondsOnlyWhileActuallyPlaying() = runTest {
        val player = PositionPlayer()
        player.replace("first", 10_000L)
        val snapshots = mutableListOf<Pair<String, Long>>()
        val observer = MobilePlayerPositionObserver(player, backgroundScope) { lease, position ->
            snapshots += lease to position
        }
        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue(snapshots.isEmpty())

        player.play()
        runCurrent()
        advanceTimeBy(14_999L)
        runCurrent()
        assertTrue(snapshots.isEmpty())
        player.position(25_000L)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf("first" to 25_000L), snapshots)
        player.position(40_000L)
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(listOf("first" to 25_000L, "first" to 40_000L), snapshots)

        snapshots.clear()
        player.buffer()
        assertTrue(snapshots.isEmpty())
        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue(snapshots.isEmpty())
        observer.close()
        player.release()
    }

    @Test
    fun pauseReplacementRemovalAndCloseRetainTheCorrectItemPosition() = runTest {
        val player = PositionPlayer()
        player.replace("first", 12_000L)
        player.play()
        val snapshots = mutableListOf<Pair<String, Long>>()
        val observer = MobilePlayerPositionObserver(player, backgroundScope) { lease, position ->
            snapshots += lease to position
        }
        player.position(23_000L)
        player.pause()
        assertTrue(snapshots.contains("first" to 23_000L))

        snapshots.clear()
        player.replace("second", 0L)
        assertEquals(listOf("first" to 23_000L), snapshots)
        player.position(31_000L)
        snapshots.clear()
        player.clearMediaItems()
        assertTrue(snapshots.contains("second" to 31_000L))
        assertTrue(snapshots.all { it.first == "second" && it.second == 31_000L })

        player.replace("third", 47_000L)
        snapshots.clear()
        observer.close()
        assertEquals(listOf("third" to 47_000L), snapshots)
        player.release()
    }

    @Test
    fun seeksDoNotSubmitImmediatelyAndZeroOrUnleasedItemsNeverSubmit() = runTest {
        val player = PositionPlayer()
        player.replace("first", 10_000L)
        val snapshots = mutableListOf<Pair<String, Long>>()
        val observer = MobilePlayerPositionObserver(player, backgroundScope) { lease, position ->
            snapshots += lease to position
        }
        repeat(20) { player.seekTo((it + 1) * 1_000L) }
        assertTrue(snapshots.isEmpty())
        observer.flush()
        assertEquals(listOf("first" to 20_000L), snapshots)
        snapshots.clear()
        player.seekTo(0L)
        observer.flush()
        player.replace(null, 17_000L)
        observer.flush()
        observer.close()
        assertTrue(snapshots.isEmpty())
        player.release()
    }

    @Test
    fun completionAndErrorsFlushPositivePositionsWithoutResetting() = runTest {
        val player = PositionPlayer()
        player.replace("first", 60_000L)
        val snapshots = mutableListOf<Pair<String, Long>>()
        val observer = MobilePlayerPositionObserver(player, backgroundScope) { lease, position ->
            snapshots += lease to position
        }
        player.end()
        assertEquals(listOf("first" to 60_000L), snapshots)
        player.replace("second", 29_000L)
        snapshots.clear()
        player.fail()
        assertTrue(snapshots.isNotEmpty())
        assertTrue(snapshots.all { it == "second" to 29_000L })
        observer.close()
        player.release()
    }

    @Test
    fun closeCancelsSamplingAndDetachesBeforeFurtherPlayerEvents() = runTest {
        val player = PositionPlayer()
        player.replace("first", 13_000L)
        player.play()
        val snapshots = mutableListOf<Pair<String, Long>>()
        val observer = MobilePlayerPositionObserver(player, backgroundScope) { lease, position ->
            snapshots += lease to position
        }
        runCurrent()
        observer.close()
        assertEquals(listOf("first" to 13_000L), snapshots)
        snapshots.clear()
        player.position(21_000L)
        player.pause()
        player.replace("second", 8_000L)
        player.play()
        advanceTimeBy(60_000L)
        runCurrent()
        observer.flush()
        observer.close()
        assertTrue(snapshots.isEmpty())
        player.release()
    }
}

@UnstableApi
private class PositionPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var state = State.Builder()
        .setAvailableCommands(Media3Player.Commands.Builder().addAllCommands().build())
        .build()

    override fun getState(): State = state

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    fun replace(lease: String?, positionMillis: Long) {
        val metadata = MediaMetadata.Builder()
            .setExtras(Bundle().apply { putString(PLAYBACK_REPORTING_LEASE_KEY, lease) })
            .build()
        val item = MediaItem.Builder().setMediaId(lease.orEmpty()).setMediaMetadata(metadata).build()
        state = state.buildUpon()
            .setPlaylist(listOf(MediaItemData.Builder(lease ?: "unleased").setMediaItem(item).build()))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Media3Player.STATE_READY)
            .setContentPositionMs(positionMillis)
            .build()
        invalidateState()
    }

    fun position(positionMillis: Long) {
        state = state.buildUpon().setContentPositionMs(positionMillis).build()
        invalidateState()
    }

    fun buffer() {
        state = state.buildUpon().setPlaybackState(Media3Player.STATE_BUFFERING).build()
        invalidateState()
    }

    fun ready() {
        state = state.buildUpon().setPlaybackState(Media3Player.STATE_READY).build()
        invalidateState()
    }

    fun end() {
        state = state.buildUpon().setPlaybackState(Media3Player.STATE_ENDED).build()
        invalidateState()
    }

    fun fail() {
        state = state.buildUpon()
            .setPlayerError(PlaybackException("Playback failed", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            .setPlaybackState(Media3Player.STATE_IDLE)
            .build()
        invalidateState()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        state = state.buildUpon()
            .setPlayWhenReady(playWhenReady, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        state = state.buildUpon().setContentPositionMs(positionMs).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        state = state.buildUpon()
            .setPlaybackState(Media3Player.STATE_IDLE)
            .setPlaylist(emptyList())
            .setCurrentMediaItemIndex(C.INDEX_UNSET)
            .setContentPositionMs(0L)
            .build()
        return Futures.immediateVoidFuture()
    }
}
