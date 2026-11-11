package io.putdotio.android.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.trash.TrashEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

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

internal fun Flow<Boolean>.networkReturns(): Flow<Unit> = flow {
    var wasOffline = false
    collect { online ->
        if (!online) {
            wasOffline = true
        } else if (wasOffline) {
            wasOffline = false
            emit(Unit)
        }
    }
}

internal suspend fun TvSession.retryFailedLoadsOnReturn(online: Flow<Boolean>) {
    online.networkReturns().collect { retryFailedLoads() }
}

internal fun TvSession.retryFailedLoads() {
    if (files.state.value.current.hasFailedRead()) files.dispatch(FilesBrowserEvent.Retry)
    search.retry()
    history.dispatch(HistoryEvent.Retry)
    trash.dispatch(TrashEvent.Retry)
}

private fun FilesFolderState.hasFailedRead(): Boolean =
    when (val failed = operation) {
        is FilesFolderOperation.Failed -> failed.intent == FilesFolderOperationIntent.Refresh
        is FilesFolderOperation.Loading -> false
        FilesFolderOperation.Idle -> when (val listed = content) {
            is FilesContent.Failed -> true
            is FilesContent.Ready -> listed.paging is FilesPaging.Failed
            is FilesContent.Empty -> listed.paging is FilesPaging.Failed
            is FilesContent.Loading -> false
        }
    }

private fun ConnectivityManager?.defaultNetworkOnline(): Boolean =
    this == null || activeNetwork?.let { getNetworkCapabilities(it) }?.reachesInternet() == true

private fun NetworkCapabilities.reachesInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
