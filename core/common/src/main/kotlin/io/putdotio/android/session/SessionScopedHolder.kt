package io.putdotio.android.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

public class SessionScopedHolder<S, K : Any, T : Any>(
    private val authState: StateFlow<S>,
    private val keyOf: (S) -> K?,
    scope: CoroutineScope,
    private val close: (T) -> Unit,
    private val onSessionEnded: (T) -> Unit = close,
) {
    private val lock = Any()
    private var active: Active<K, T>? = null

    init {
        scope.launch { authState.collect { reconcile() } }
    }

    public fun valueFor(key: K, create: () -> T): T? =
        synchronized(lock) {
            if (currentKey() != key) return@synchronized null
            active?.takeIf { it.key == key }?.let { return@synchronized it.value }

            active?.let { close(it.value) }
            active = null
            if (currentKey() != key) return@synchronized null

            val value = create()
            if (currentKey() != key) {
                close(value)
                null
            } else {
                active = Active(key, value)
                value
            }
        }

    public fun clear() {
        synchronized(lock) {
            active?.let { close(it.value) }
            active = null
        }
    }

    private fun reconcile() {
        synchronized(lock) {
            val current = active ?: return
            if (current.key != currentKey()) {
                onSessionEnded(current.value)
                active = null
            }
        }
    }

    private fun currentKey(): K? = keyOf(authState.value)

    private class Active<K, T>(val key: K, val value: T)
}
