package io.putdotio.android.playback

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

internal class MobileSleepTimer(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    data class Run(val minutes: Int, val endsAt: Long)

    private val state = MutableStateFlow<Run?>(null)

    val run: StateFlow<Run?> = state.asStateFlow()

    fun start(minutes: Int) {
        state.value = Run(minutes, clock() + minutes * MILLIS_PER_MINUTE)
    }

    fun cancel() {
        state.value = null
    }

    fun remainingMillis(run: Run): Long = (run.endsAt - clock()).coerceAtLeast(0L)

    suspend fun pauseWhenDue(pause: () -> Unit) {
        state.collectLatest { run ->
            if (run == null) return@collectLatest
            while (remainingMillis(run) > 0L) delay(remainingMillis(run).coerceAtMost(MAX_WAIT_MILLIS))
            if (state.compareAndSet(run, null)) pause()
        }
    }

    companion object {
        val Shared = MobileSleepTimer()

        val CHOICES = listOf(15, 30, 45, 60)

        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MAX_WAIT_MILLIS = 5_000L
    }
}
