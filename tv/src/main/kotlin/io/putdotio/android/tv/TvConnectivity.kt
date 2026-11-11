package io.putdotio.android.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface TvConnectivity {
    val online: StateFlow<Boolean>
}

internal class SystemTvConnectivity(context: Context) : TvConnectivity {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(connectivity.defaultNetworkOnline())
    override val online: StateFlow<Boolean> = state.asStateFlow()
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            state.value = capabilities.reachesInternet()
        }

        override fun onLost(network: Network) {
            state.value = false
        }
    }

    fun start() {
        if (registered) return
        registered = connectivity?.runCatching { registerDefaultNetworkCallback(callback) }?.isSuccess == true
        if (!registered) state.value = true
    }

    fun stop() {
        if (registered) connectivity?.runCatching { unregisterNetworkCallback(callback) }
        registered = false
    }
}

@Composable
internal fun rememberTvConnectivity(): TvConnectivity {
    val context = LocalContext.current.applicationContext
    val connectivity = remember(context) { SystemTvConnectivity(context) }
    DisposableEffect(connectivity) {
        connectivity.start()
        onDispose { connectivity.stop() }
    }
    return connectivity
}

private fun ConnectivityManager?.defaultNetworkOnline(): Boolean =
    this == null || activeNetwork?.let { getNetworkCapabilities(it) }?.reachesInternet() == true

private fun NetworkCapabilities.reachesInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
