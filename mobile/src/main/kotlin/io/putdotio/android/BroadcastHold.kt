package io.putdotio.android

import android.content.BroadcastReceiver
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a receiver's broadcast open while its work runs, but ends it after [limitMillis] at the
 * latest: one delivered on the foreground queue gets 10 s before the system reports it stuck. Work
 * still running then goes on best-effort. [release] may be called any number of times.
 */
internal class BroadcastHold(
    private val finish: () -> Unit,
    limitMillis: Long = BROADCAST_HOLD_MILLIS,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val released = AtomicBoolean(false)
    private val timeout = Runnable(::release)

    init {
        handler.postDelayed(timeout, limitMillis)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        handler.removeCallbacks(timeout)
        finish()
    }
}

/** Under the 10 s a foreground-queue broadcast gets, with room for the main thread to run the release. */
internal const val BROADCAST_HOLD_MILLIS = 8_000L

/** [BroadcastReceiver.goAsync] behind a [BroadcastHold]; outside a broadcast, as in a test, there is nothing to end. */
internal fun BroadcastReceiver.holdBroadcast(): BroadcastHold {
    val pending: BroadcastReceiver.PendingResult? = goAsync()
    return BroadcastHold({ pending?.finish() })
}
