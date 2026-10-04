package io.putdotio.android.tv.auth

import io.putdotio.sdk.auth.DeviceCodeAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The device-code screen's attempts, driven through the SDK orchestrator one at a time
 * on [scope]. A replacement joins the previous collector first, and every event carries
 * the generation it belongs to, so an abandoned poll can neither repaint the screen nor
 * persist a token after the user asked for a new code.
 */
internal class TvDeviceLinking(
    private val state: MutableStateFlow<TvAuthState>,
    private val sessionGateway: TvSessionGateway,
    private val tokens: TvSessionTokens,
    private val scope: CoroutineScope,
    /** Persists a linked token; runs non-cancellable within the attempt that linked it. */
    private val onLinked: suspend (linked: DeviceCodeAuthState.Linked, sessionExpired: Boolean) -> Unit,
) {
    private var attempt: Job? = null
    private var generation = 0L

    fun start(sessionExpired: Boolean) {
        state.value = TvAuthState.Linking(TvLinkPhase.RequestingCode, sessionExpired)
        val attemptGeneration = ++generation
        // The SDK reports its own failures as Failed states; anything else escaping the
        // flow would otherwise strand the screen on "Getting a code" with no action.
        @Suppress("TooGenericExceptionCaught")
        attempt = scope.launch {
            try {
                sessionGateway.link().collect { linkState -> onLinkState(attemptGeneration, linkState, sessionExpired) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (attemptGeneration == generation) stop(TvLinkStop.Failed(TvLinkFailure.SERVER), sessionExpired)
            }
        }
    }

    /**
     * Forgets the session's token on the gateway and in storage, then offers a new code. A
     * store that cannot be cleared stops on [TvLinkStop.StorageUnavailable] instead.
     */
    suspend fun restart(sessionExpired: Boolean) = withContext(NonCancellable) {
        if (tokens.clear()) {
            start(sessionExpired)
        } else {
            stop(TvLinkStop.StorageUnavailable, sessionExpired)
        }
    }

    fun stop(
        reason: TvLinkStop,
        sessionExpired: Boolean,
    ) {
        state.value = TvAuthState.Linking(TvLinkPhase.Stopped(reason), sessionExpired)
    }

    /** Cancels the running attempt, if any, and waits for its collector to finish. */
    suspend fun cancelAndJoin() {
        attempt?.cancelAndJoin()
    }

    private suspend fun onLinkState(
        attemptGeneration: Long,
        linkState: DeviceCodeAuthState,
        sessionExpired: Boolean,
    ) {
        if (attemptGeneration != generation) {
            return
        }
        when (linkState) {
            DeviceCodeAuthState.Requesting -> Unit
            is DeviceCodeAuthState.AwaitingLink ->
                state.value = TvAuthState.Linking(TvLinkPhase.AwaitingLink(linkState.code), sessionExpired)
            DeviceCodeAuthState.Validating ->
                state.value = TvAuthState.Linking(TvLinkPhase.Validating, sessionExpired)
            is DeviceCodeAuthState.Linked ->
                withContext(NonCancellable) { onLinked(linkState, sessionExpired) }
            is DeviceCodeAuthState.Expired -> stop(TvLinkStop.CodeExpired, sessionExpired)
            is DeviceCodeAuthState.Failed ->
                stop(TvLinkStop.Failed(linkState.error.toTvLinkFailure()), sessionExpired)
        }
    }
}
