package io.putdotio.android

import android.content.BroadcastReceiver
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

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

internal const val BROADCAST_HOLD_MILLIS = 8_000L

internal fun BroadcastReceiver.holdBroadcast(): BroadcastHold {
    val pending: BroadcastReceiver.PendingResult? = goAsync()
    return BroadcastHold({ pending?.finish() })
}
