package io.putdotio.android.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPositionWriterTest {
    @Test
    fun serializesWritesAndKeepsOnlyTheLatestPendingPosition() = runTest {
        val calls = mutableListOf<Pair<Long, Double>>()
        val first = CompletableDeferred<Unit>()
        val writer = PlaybackPositionWriter(backgroundScope) { fileId, seconds ->
            calls += fileId to seconds
            if (calls.size == 1) first.await()
            PlaybackRepositoryResult.Success(Unit)
        }
        val lease = writer.register(1L) { true }
        writer.offer(lease, 15_000L)
        runCurrent()
        repeat(100) { writer.offer(lease, 16_000L + it * 1_000L) }
        runCurrent()
        assertEquals(listOf(1L to 15.0), calls)
        first.complete(Unit)
        runCurrent()
        assertEquals(listOf(1L to 15.0, 1L to 115.0), calls)
        writer.offer(lease, 115_000L)
        writer.offer(lease, 115_999L)
        writer.offer(lease, 0L)
        writer.offer("unknown", 25_000L)
        runCurrent()
        assertEquals(2, calls.size)
        writer.close()
    }

    @Test
    fun rechecksAuthorizationBeforeTheRequestAndDiscardsRevokedSnapshots() = runTest {
        var allowed = true
        var calls = 0
        var cancelled = false
        val writer = PlaybackPositionWriter(backgroundScope) { _, _ ->
            calls++
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val lease = writer.register(1L) { allowed }
        writer.offer(lease, 15_000L)
        allowed = false
        runCurrent()
        assertEquals(0, calls)
        writer.reconcile()
        allowed = true
        writer.offer(lease, 30_000L)
        runCurrent()
        writer.offer(lease, 45_000L)
        allowed = false
        writer.reconcile()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(1, calls)
        allowed = true
        runCurrent()
        assertEquals(1, calls)
        writer.close()
    }

    @Test
    fun reopeningTheSameFileInvalidatesItsPreviousWriter() = runTest {
        val calls = mutableListOf<Double>()
        val writer = PlaybackPositionWriter(backgroundScope) { _, seconds ->
            calls += seconds
            PlaybackRepositoryResult.Success(Unit)
        }
        val old = writer.register(1L) { true }
        writer.offer(old, 90_000L)
        val current = writer.register(1L) { true }
        writer.offer(current, 12_000L)
        writer.offer(old, 91_000L)
        runCurrent()
        assertEquals(listOf(12.0), calls)
        writer.close()
    }

    @Test
    fun failuresKeepTheirCauseWithoutImmediateOrDuplicateFlushRetries() = runTest {
        val cause = IllegalStateException("offline")
        val failure = PlaybackFailure.NetworkUnavailable(cause)
        var calls = 0
        val writer = PlaybackPositionWriter(backgroundScope) { _, _ ->
            calls++
            if (calls == 1) PlaybackRepositoryResult.Failure(failure) else PlaybackRepositoryResult.Success(Unit)
        }
        val lease = writer.register(1L) { true }
        writer.offer(lease, 15_000L)
        runCurrent()
        assertEquals(failure, writer.failure.value)
        repeat(10) { writer.offer(lease, 15_000L) }
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, calls)
        writer.offer(lease, 30_000L)
        runCurrent()
        assertEquals(2, calls)
        assertNull(writer.failure.value)
        writer.close()
    }

    @Test
    fun hungRequestTimesOutAndReleasesTheLatestPosition() = runTest {
        val calls = mutableListOf<Double>()
        val writer = PlaybackPositionWriter(backgroundScope) { _, seconds ->
            calls += seconds
            if (calls.size == 1) awaitCancellation()
            PlaybackRepositoryResult.Success(Unit)
        }
        val lease = writer.register(1L) { true }
        writer.offer(lease, 15_000L)
        runCurrent()
        writer.offer(lease, 30_000L)
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(listOf(15.0, 30.0), calls)
        writer.close()
        writer.offer(lease, 45_000L)
        runCurrent()
        assertEquals(2, calls.size)
    }
}
