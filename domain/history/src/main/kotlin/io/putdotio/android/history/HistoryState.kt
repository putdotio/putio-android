package io.putdotio.android.history

import io.putdotio.android.PutioFailure

@JvmInline
public value class HistoryRequestId(internal val value: Long)

public sealed interface HistoryPaging {
    public data object Complete : HistoryPaging
    public data class Available(val before: HistoryEventId) : HistoryPaging
    public data class Loading(val before: HistoryEventId, val requestId: HistoryRequestId) : HistoryPaging
    public data class Failed(val before: HistoryEventId, val failure: PutioFailure) : HistoryPaging
}

public sealed interface HistoryContent {
    public data object Disabled : HistoryContent
    public data class Loading(val requestId: HistoryRequestId) : HistoryContent
    public data object Empty : HistoryContent
    public data class Ready(val items: List<HistoryItem>, val paging: HistoryPaging) : HistoryContent {
        init { require(items.isNotEmpty()) }
    }
    public data class Failed(val failure: PutioFailure) : HistoryContent
}

public sealed interface HistoryClearing {
    public data object Idle : HistoryClearing
    public data object AwaitingConfirmation : HistoryClearing
    public data class Clearing(val requestId: HistoryRequestId) : HistoryClearing
    public data class Failed(val failure: PutioFailure) : HistoryClearing
}

@ConsistentCopyVisibility
public data class HistoryState internal constructor(
    val content: HistoryContent,
    val clearing: HistoryClearing = HistoryClearing.Idle,
    internal val authoritativeFailure: PutioFailure.AuthenticationRequired? = null,
    internal val consumedBefore: Set<HistoryEventId> = emptySet(),
    internal val nextRequestValue: Long = 1L,
)

public sealed interface HistoryEvent {
    public data class SetEnabled(val enabled: Boolean) : HistoryEvent
    public data object LoadNextPage : HistoryEvent
    public data object Retry : HistoryEvent
    public data object RequestClear : HistoryEvent
    public data object DismissClear : HistoryEvent
    public data object ConfirmClear : HistoryEvent
    public data class OpenFile(val fileId: HistoryFileId) : HistoryEvent
    public data class LoadSucceeded(val requestId: HistoryRequestId, val page: HistoryPage) : HistoryEvent
    public data class LoadFailed(val requestId: HistoryRequestId, val failure: PutioFailure) : HistoryEvent
    public data class ClearSucceeded(val requestId: HistoryRequestId) : HistoryEvent
    public data class ClearFailed(val requestId: HistoryRequestId, val failure: PutioFailure) : HistoryEvent
}

public sealed interface HistoryEffect {
    public data class Load(val before: HistoryEventId?, val requestId: HistoryRequestId) : HistoryEffect
    public data class Clear(val requestId: HistoryRequestId) : HistoryEffect
    public data class NavigateToFile(val fileId: HistoryFileId) : HistoryEffect
}

public data class HistoryTransition(
    val state: HistoryState,
    val effect: HistoryEffect? = null,
    val consumed: Boolean = true,
)

public object HistoryReducer {
    public fun start(historyEnabled: Boolean): HistoryTransition {
        if (!historyEnabled) return HistoryTransition(HistoryState(HistoryContent.Disabled), consumed = false)
        val requestId = HistoryRequestId(1L)
        return HistoryTransition(
            HistoryState(HistoryContent.Loading(requestId), nextRequestValue = 2L),
            HistoryEffect.Load(before = null, requestId),
        )
    }

    internal fun reduce(state: HistoryState, event: HistoryEvent): HistoryTransition =
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
