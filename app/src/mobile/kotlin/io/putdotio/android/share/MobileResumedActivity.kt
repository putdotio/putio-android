package io.putdotio.android.share

import android.annotation.SuppressLint
import android.app.Activity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The Activity currently in the resumed state, reported by the Activity itself so a
 * service created after the resume still sees it. Chooser launches need a visible window.
 */
// Cleared in onPause, so the reference never outlives the visible Activity.
@SuppressLint("StaticFieldLeak")
internal object MobileResumedActivity {
    var current: Activity? = null
        private set
    private val waiters = mutableListOf<(Activity) -> Unit>()

    fun resumed(activity: Activity) {
        val pending = synchronized(waiters) {
            current = activity
            waiters.toList().also { waiters.clear() }
        }
        pending.forEach { it(activity) }
    }

    fun paused(activity: Activity) {
        if (current === activity) current = null
    }

    /** Cancellation may arrive off the main thread, so registration and removal share one lock. */
    suspend fun await(): Activity = suspendCancellableCoroutine { continuation ->
        val waiter: (Activity) -> Unit = { if (continuation.isActive) continuation.resume(it) }
        val now = synchronized(waiters) { current.also { if (it == null) waiters += waiter } }
        if (now != null) continuation.resume(now)
        continuation.invokeOnCancellation { synchronized(waiters) { waiters -= waiter } }
    }
}
