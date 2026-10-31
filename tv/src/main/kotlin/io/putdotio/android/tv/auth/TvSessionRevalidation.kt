package io.putdotio.android.tv.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.putdotio.android.auth.NetworkReturns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class TvSessionRevalidation(
    private val authController: TvAuthController,
    private val scope: CoroutineScope,
    private val delays: List<Duration> = DEFAULT_DELAYS,
) {
    private var attempts: Job? = null
    private var returnedDuringCheck = false

    init {
        scope.launch {
            authController.state.collect { state ->
                when {
                    state.isCheckingSession() -> Unit
                    returnedDuringCheck && state is TvAuthState.ValidationUnavailable -> start()
                    else -> returnedDuringCheck = false
                }
            }
        }
    }

    fun onNetworkReturned() {
        val state = authController.state.value
        when {
            state is TvAuthState.ValidationUnavailable -> start()
            state.isCheckingSession() -> returnedDuringCheck = true
        }
    }

    private fun start() {
        returnedDuringCheck = false
        attempts?.cancel()
        attempts = scope.launch {
            for (wait in delays) {
                delay(wait)
                if (!awaitsPutio()) return@launch
                authController.retryValidation()
                if (!awaitsPutio()) return@launch
            }
        }
    }

    private fun awaitsPutio(): Boolean = authController.state.value is TvAuthState.ValidationUnavailable

    private companion object {
        val DEFAULT_DELAYS = listOf(Duration.ZERO, 5.seconds, 30.seconds)
    }
}

private fun TvAuthState.isCheckingSession(): Boolean =
    this == TvAuthState.Initializing || this == TvAuthState.RestoringSession || this is TvAuthState.ValidatingSession

internal fun watchNetworkReturns(
    context: Context,
    onReturn: () -> Unit,
) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
    val returns = NetworkReturns(
        initiallyValidated = connectivity.activeNetwork
            ?.let(connectivity::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        onReturn = onReturn,
    )
    connectivity.registerDefaultNetworkCallback(
        object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                returns.onLost()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                returns.onCapabilities(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        },
    )
}
