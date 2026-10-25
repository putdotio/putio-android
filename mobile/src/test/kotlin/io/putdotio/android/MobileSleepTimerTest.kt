package io.putdotio.android

import io.putdotio.android.playback.MobileSleepTimer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MobileSleepTimerTest {
    @Test
    fun theTimerPausesOnceWhenItRunsOutThenTurnsOff() = runTest {
        val timer = MobileSleepTimer(clock = { testScheduler.currentTime })
        var pauses = 0
        val job = launch { timer.pauseWhenDue { pauses += 1 } }
        runCurrent()

        timer.start(15)
        runCurrent()
        assertEquals(15 * 60_000L, timer.run.value?.let(timer::remainingMillis))
        advanceTimeBy(15 * 60_000L - 1)
        runCurrent()
        assertEquals(0, pauses)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, pauses)
        assertNull(timer.run.value)

        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertEquals(1, pauses)
        job.cancel()
    }

    @Test
    fun cancellingOrChoosingAgainReplacesTheRunningTimer() = runTest {
        val timer = MobileSleepTimer(clock = { testScheduler.currentTime })
        var pauses = 0
        val job = launch { timer.pauseWhenDue { pauses += 1 } }

        timer.start(15)
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        timer.cancel()
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertEquals(0, pauses)

        // Off that lands once the wait is over, before the timer acts on it, still wins.
        timer.start(15)
        runCurrent()
        advanceTimeBy(15 * 60_000L)
        timer.cancel()
        runCurrent()
        assertEquals(0, pauses)

        timer.start(15)
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        // A new choice counts from now, not from the first one.
        timer.start(30)
        runCurrent()
        advanceTimeBy(30 * 60_000L - 1)
        runCurrent()
        assertEquals(0, pauses)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, pauses)
        job.cancel()
    }
}
