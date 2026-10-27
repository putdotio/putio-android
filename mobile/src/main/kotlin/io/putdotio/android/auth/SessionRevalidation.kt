package io.putdotio.android.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Asks put.io again about a session it could not answer for: when a session opens offline, and
 * each time a validated network returns. A session that opened offline is confirmed in place, and
 * `Couldn’t check your session` retries. Each start makes at most [delays] attempts and replaces
 * the previous start's; nothing runs on a timer while the network stays as it is.
 */
internal class SessionRevalidation(
    private val authController: MobileAuthController,
    private val scope: CoroutineScope,
    private val delays: List<Duration> = DEFAULT_DELAYS,
) {
    private var attempts: Job? = null

    init {
        scope.launch {
            var opened: MobileAuthSessionId? = null
            authController.state.collect { state ->
                val offline = (state as? MobileAuthState.SignedIn)?.takeUnless { it.validated }?.sessionId
                if (offline != null && offline != opened) onNetworkReturned()
                opened = offline
            }
        }
    }

    /** The default network validated after it was missing or unvalidated. */
    fun onNetworkReturned() {
        if (!authController.state.value.awaitsPutio()) return
        attempts?.cancel()
        attempts = scope.launch {
            for (wait in delays) {
                delay(wait)
                if (attempt()) return@launch
            }
        }
    }

    /** True once nothing is left to ask put.io. */
    private suspend fun attempt(): Boolean =
        when (authController.state.value) {
            is MobileAuthState.SignedIn -> authController.revalidate()
            is MobileAuthState.ValidationUnavailable ->
                authController.retryValidation() && authController.state.value !is MobileAuthState.ValidationUnavailable
            else -> true
        }

    private companion object {
        val DEFAULT_DELAYS = listOf(Duration.ZERO, 5.seconds, 30.seconds)
    }
}

private fun MobileAuthState.awaitsPutio(): Boolean =
    this is MobileAuthState.ValidationUnavailable || this is MobileAuthState.SignedIn && !validated

/**
 * Turns default-network callbacks into returns: only a network that was missing or unvalidated and
 * then validates counts, so capability updates on a working network and put.io outages never retry.
 */
internal class NetworkReturns(
    initiallyValidated: Boolean,
    private val onReturn: () -> Unit,
) {
    private var waiting = !initiallyValidated

    @Synchronized
    fun onLost() {
        waiting = true
    }

    fun onCapabilities(validated: Boolean) {
        val returned = synchronized(this) {
            if (!validated) {
                waiting = true
                false
            } else {
                waiting.also { waiting = false }
            }
        }
        if (returned) onReturn()
    }
}
