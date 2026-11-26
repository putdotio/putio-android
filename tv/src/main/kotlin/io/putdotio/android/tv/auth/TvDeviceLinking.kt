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

internal class TvDeviceLinking(
    private val state: MutableStateFlow<TvAuthState>,
    private val sessionGateway: TvSessionGateway,
    private val tokens: TvSessionTokens,
    private val scope: CoroutineScope,
    private val onLinked: suspend (linked: DeviceCodeAuthState.Linked, sessionExpired: Boolean) -> Unit,
) {
    private var attempt: Job? = null
    private var generation = 0L
    private var silentRenewals = 0

    private var shownCode: String? = null

    fun start(sessionExpired: Boolean) {
        silentRenewals = 0
        shownCode = null
        state.value = TvAuthState.Linking(TvLinkPhase.RequestingCode, sessionExpired)
        launchAttempt(sessionExpired)
    }

    private fun renewSilently(sessionExpired: Boolean) {
        silentRenewals++
        launchAttempt(sessionExpired)
    }

    private fun launchAttempt(sessionExpired: Boolean) {
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
        state.value = TvAuthState.Linking(TvLinkPhase.Stopped(reason, shownCode), sessionExpired)
    }

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
            is DeviceCodeAuthState.AwaitingLink -> {
                shownCode = linkState.code
                state.value = TvAuthState.Linking(TvLinkPhase.AwaitingLink(linkState.code), sessionExpired)
            }
            DeviceCodeAuthState.Validating ->
                state.value = TvAuthState.Linking(TvLinkPhase.Validating(shownCode), sessionExpired)
            is DeviceCodeAuthState.Linked -> {
                shownCode = null
                withContext(NonCancellable) { onLinked(linkState, sessionExpired) }
            }
            is DeviceCodeAuthState.Expired ->
                if (linkState.reason == DeviceCodeAuthState.Expired.Reason.BUDGET_ELAPSED &&
                    silentRenewals < MAX_SILENT_RENEWALS
                ) {
                    renewSilently(sessionExpired)
                } else {
                    stop(TvLinkStop.CodeExpired, sessionExpired)
                }
            is DeviceCodeAuthState.Failed ->
                stop(TvLinkStop.Failed(linkState.error.toTvLinkFailure()), sessionExpired)
        }
    }
}

internal const val MAX_SILENT_RENEWALS = 3
