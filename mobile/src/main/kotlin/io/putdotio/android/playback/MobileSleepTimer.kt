package io.putdotio.android.playback

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Audio's sleep timer: when it runs out, the session pauses. The audio service runs in the app's
 * process, so Now Playing sets the timer and the service carries it out, with no session command
 * between them.
 */
internal class MobileSleepTimer(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    /** A running timer: the choice it was started with, and when it runs out on [clock]'s timeline. */
    data class Run(val minutes: Int, val endsAt: Long)

    private val state = MutableStateFlow<Run?>(null)

    /** The running timer; null while it is off. */
    val run: StateFlow<Run?> = state.asStateFlow()

    fun start(minutes: Int) {
        state.value = Run(minutes, clock() + minutes * MILLIS_PER_MINUTE)
    }

    fun cancel() {
        state.value = null
    }

    /** What is left of [run], never below zero. */
    fun remainingMillis(run: Run): Long = (run.endsAt - clock()).coerceAtLeast(0L)

    /**
     * Pauses through [pause] each time the timer runs out, then turns it off; runs until cancelled.
     * The wait goes in short steps: a delay counts uptime, which stops in deep sleep while the
     * deadline's clock does not.
     */
    suspend fun pauseWhenDue(pause: () -> Unit) {
        state.collectLatest { run ->
            if (run == null) return@collectLatest
            while (remainingMillis(run) > 0L) delay(remainingMillis(run).coerceAtMost(MAX_WAIT_MILLIS))
            // Off or a new choice can land after the wait ends; only the run still current pauses.
            if (state.compareAndSet(run, null)) pause()
        }
    }

    companion object {
        /** The process's one timer, as there is one audio session. */
        val Shared = MobileSleepTimer()

        val CHOICES = listOf(15, 30, 45, 60)

        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MAX_WAIT_MILLIS = 5_000L
    }
}
