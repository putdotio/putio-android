package io.putdotio.android.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Holds one value for the signed-in session so it never outlives that session.
 *
 * [valueFor] returns the held value only while [key] is still the current session, builds a
 * fresh one when the session changed hands, and closes a value whose session ended while it
 * was being built instead of returning it. Every auth-state change closes a held value whose
 * session is no longer current.
 *
 * @param authState the auth state the session key is read from on every check.
 * @param keyOf the session key of a signed-in state, or null when signed out.
 * @param scope collects [authState] for the holder's lifetime; usually `viewModelScope`.
 * @param close releases a value that is replaced, discarded, or [clear]ed.
 * @param onSessionEnded releases a value whose session ended; defaults to [close].
 */
internal class SessionScopedHolder<S, K : Any, T : Any>(
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

    /** The value for [key], built with [create] when none is held; null once [key] is not current. */
    fun valueFor(key: K, create: () -> T): T? =
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

    /** Closes the held value; call from the owner's `onCleared`. */
    fun clear() {
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
