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

    /** A return that came while a session check was still running; its failure starts attempts. */
    private var returnedDuringCheck = false

    init {
        scope.launch {
            var opened: MobileAuthSessionId? = null
            authController.state.collect { state ->
                val offline = (state as? MobileAuthState.SignedIn)?.takeUnless { it.validated }?.sessionId
                val openedOffline = offline != null && offline != opened
                val checkFailedAfterReturn = returnedDuringCheck && state.awaitsPutio()
                when {
                    !state.isSettled() -> Unit
                    openedOffline || checkFailedAfterReturn -> start()
                    else -> returnedDuringCheck = false
                }
                opened = offline
            }
        }
    }

    /** The default network validated after it was missing or unvalidated. */
    fun onNetworkReturned() {
        val state = authController.state.value
        if (state.awaitsPutio()) {
            start()
        } else if (!state.isSettled()) {
            returnedDuringCheck = true
        }
    }

    private fun start() {
        returnedDuringCheck = false
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
