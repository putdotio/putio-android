package io.putdotio.android.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

public class NetworkReturns(
    private val onReturn: () -> Unit,
) {
    private var waiting = false

    @Synchronized
    public fun onLost() {
        waiting = true
    }

    public fun onCapabilities(validated: Boolean) {
        report(synchronized(this) { settle(validated) })
    }

    public fun onRegistered(currentlyValidated: () -> Boolean) {
        report(synchronized(this) { settle(currentlyValidated()) })
    }

    private fun settle(validated: Boolean): Boolean =
        if (!validated) {
            waiting = true
            false
        } else {
            waiting.also { waiting = false }
        }

    private fun report(returned: Boolean) {
        if (returned) onReturn()
    }
}

public fun watchNetworkReturns(
    context: Context,
    onValidated: () -> Unit = {},
    onReturn: () -> Unit,
) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
    val returns = NetworkReturns(onReturn)
    connectivity.registerDefaultNetworkCallback(
        object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                returns.onLost()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated) onValidated()
                returns.onCapabilities(validated)
            }
        },
    )
    returns.onRegistered {
        connectivity.activeNetwork
            ?.let(connectivity::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
}
