package io.putdotio.android.history

import io.putdotio.android.files.FilesFailure

@JvmInline
value class HistoryRequestId(val value: Long)

sealed interface HistoryPaging {
    data object Complete : HistoryPaging
    data class Available(val before: HistoryEventId) : HistoryPaging
    data class Loading(val before: HistoryEventId, val requestId: HistoryRequestId) : HistoryPaging
    data class Failed(val before: HistoryEventId, val failure: FilesFailure) : HistoryPaging
}

sealed interface HistoryContent {
    data object Disabled : HistoryContent
    data class Loading(val requestId: HistoryRequestId) : HistoryContent
    data object Empty : HistoryContent
    data class Ready(val items: List<HistoryItem>, val paging: HistoryPaging) : HistoryContent {
        init { require(items.isNotEmpty()) }
    }
    data class Failed(val failure: FilesFailure) : HistoryContent
}

sealed interface HistoryClearing {
    data object Idle : HistoryClearing
    data object AwaitingConfirmation : HistoryClearing
    data class Clearing(val requestId: HistoryRequestId) : HistoryClearing
    data class Failed(val failure: FilesFailure) : HistoryClearing
}

@ConsistentCopyVisibility
data class HistoryState internal constructor(
    val content: HistoryContent,
    val clearing: HistoryClearing = HistoryClearing.Idle,
    internal val consumedBefore: Set<HistoryEventId> = emptySet(),
    internal val nextRequestValue: Long = 1L,
)

sealed interface HistoryEvent {
    data class SetEnabled(val enabled: Boolean) : HistoryEvent
    data object LoadNextPage : HistoryEvent
    data object Retry : HistoryEvent
    data object RequestClear : HistoryEvent
    data object DismissClear : HistoryEvent
    data object ConfirmClear : HistoryEvent
    data class OpenFile(val fileId: HistoryFileId) : HistoryEvent
    data class LoadSucceeded(val requestId: HistoryRequestId, val page: HistoryPage) : HistoryEvent
    data class LoadFailed(val requestId: HistoryRequestId, val failure: FilesFailure) : HistoryEvent
    data class ClearSucceeded(val requestId: HistoryRequestId) : HistoryEvent
    data class ClearFailed(val requestId: HistoryRequestId, val failure: FilesFailure) : HistoryEvent
}

sealed interface HistoryEffect {
    data class Load(val before: HistoryEventId?, val requestId: HistoryRequestId) : HistoryEffect
    data class Clear(val requestId: HistoryRequestId) : HistoryEffect
    data class NavigateToFile(val fileId: HistoryFileId) : HistoryEffect
}

data class HistoryTransition(
    val state: HistoryState,
    val effect: HistoryEffect? = null,
    val consumed: Boolean = true,
)

object HistoryReducer {
    fun start(historyEnabled: Boolean): HistoryTransition {
        if (!historyEnabled) return HistoryTransition(HistoryState(HistoryContent.Disabled), consumed = false)
        val requestId = HistoryRequestId(1L)
        return HistoryTransition(
            HistoryState(HistoryContent.Loading(requestId), nextRequestValue = 2L),
            HistoryEffect.Load(before = null, requestId),
        )
    }

    fun reduce(state: HistoryState, event: HistoryEvent): HistoryTransition =
        when (event) {
            is HistoryEvent.SetEnabled -> state.setEnabled(event.enabled)
            HistoryEvent.LoadNextPage -> state.loadNextPage()
            HistoryEvent.Retry -> state.retry()
            HistoryEvent.RequestClear -> state.requestClear()
            HistoryEvent.DismissClear -> state.dismissClear()
            HistoryEvent.ConfirmClear -> state.confirmClear()
            is HistoryEvent.OpenFile -> HistoryTransition(state, HistoryEffect.NavigateToFile(event.fileId))
            is HistoryEvent.LoadSucceeded -> state.loadSucceeded(event)
            is HistoryEvent.LoadFailed -> state.loadFailed(event)
            is HistoryEvent.ClearSucceeded -> state.clearSucceeded(event)
            is HistoryEvent.ClearFailed -> state.clearFailed(event)
        }
}
