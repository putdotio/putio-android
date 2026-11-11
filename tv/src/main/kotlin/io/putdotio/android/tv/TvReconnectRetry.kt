package io.putdotio.android.tv

import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashEvent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    online.networkReturns().collectLatest {
        val inFlight = reconnectReads().filter { it.inFlight }
        retryFailedLoads()
        coroutineScope { inFlight.forEach { read -> launch { read.retryIfFailed() } } }
    }
}

internal fun TvSession.retryFailedLoads() {
    reconnectReads().forEach { it.retry() }
}

private fun TvSession.reconnectReads(): List<ReconnectRead<*>> = listOf(
    ReconnectRead(files.state, { it.current.isReading() }) {
        if (files.state.value.current.hasFailedRead()) files.dispatch(FilesBrowserEvent.Retry)
    },
    ReconnectRead(search.state, { it.content.isReading() }) { search.retry() },
    ReconnectRead(history.state, { it.content.isReading() }) { history.dispatch(HistoryEvent.Retry) },
    ReconnectRead(trash.state, { it.content.isReading() }) { trash.dispatch(TrashEvent.Retry) },
)

private class ReconnectRead<S>(
    private val state: StateFlow<S>,
    private val reading: (S) -> Boolean,
    val retry: () -> Unit,
) {
    val inFlight: Boolean get() = reading(state.value)

    suspend fun retryIfFailed() {
        val settled = withTimeoutOrNull(READ_SETTLE_TIMEOUT) { state.first { !reading(it) } }
        if (settled != null) retry()
    }

    private companion object {
        val READ_SETTLE_TIMEOUT = 15.seconds
    }
}

private fun FilesFolderState.isReading(): Boolean =
    when (val running = operation) {
        is FilesFolderOperation.Loading -> running.intent == FilesFolderOperationIntent.Refresh
        else -> when (val listed = content) {
            is FilesContent.Loading -> true
            is FilesContent.Ready -> listed.paging is FilesPaging.Loading
            is FilesContent.Empty -> listed.paging is FilesPaging.Loading
            is FilesContent.Failed -> false
        }
    }

private fun SearchContent.isReading(): Boolean =
    when (this) {
        is SearchContent.Loading -> true
        is SearchContent.Ready -> paging is SearchPaging.Loading
        is SearchContent.Empty -> paging is SearchPaging.Loading
        SearchContent.Idle, is SearchContent.Debouncing, is SearchContent.Failed -> false
    }

private fun HistoryContent.isReading(): Boolean =
    this is HistoryContent.Loading || (this is HistoryContent.Ready && paging is HistoryPaging.Loading)

private fun TrashContent.isReading(): Boolean =
    this is TrashContent.Loading || (this is TrashContent.Loaded && isBusy)

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
