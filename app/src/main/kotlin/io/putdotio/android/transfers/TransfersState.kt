package io.putdotio.android.transfers

import io.putdotio.android.files.FilesFailure

@JvmInline
value class TransfersRequestId(val value: Long)

sealed interface TransfersPaging {
    data object Complete : TransfersPaging
    data class Available(val cursor: TransferCursor) : TransfersPaging
    data class Loading(val cursor: TransferCursor, val requestId: TransfersRequestId) : TransfersPaging
    data class Failed(val cursor: TransferCursor, val failure: FilesFailure) : TransfersPaging
}

sealed interface TransfersContent {
    data class InitialLoading(val requestId: TransfersRequestId) : TransfersContent
    data object Empty : TransfersContent
    data class Ready(val items: List<TransferItem>, val paging: TransfersPaging) : TransfersContent {
        init { require(items.isNotEmpty()) }
    }
    data class Failed(val failure: FilesFailure) : TransfersContent
}

sealed interface TransfersRefresh {
    data object Idle : TransfersRefresh
    data class Refreshing(val requestId: TransfersRequestId) : TransfersRefresh
    data class Polling(val requestId: TransfersRequestId) : TransfersRefresh
    data class Failed(val failure: FilesFailure) : TransfersRefresh
}

internal val TransfersRefresh.isRunning: Boolean
    get() = this is TransfersRefresh.Refreshing || this is TransfersRefresh.Polling

internal fun TransfersRefresh.hasRequest(requestId: TransfersRequestId): Boolean =
    when (this) {
        is TransfersRefresh.Refreshing -> this.requestId == requestId
        is TransfersRefresh.Polling -> this.requestId == requestId
        TransfersRefresh.Idle,
        is TransfersRefresh.Failed,
        -> false
    }

sealed interface TransferAction {
    data class Add(val submission: TransferSubmission) : TransferAction
    data class Cancel(val id: TransferId) : TransferAction
    data class Retry(val id: TransferId) : TransferAction
    data object Clean : TransferAction
}

sealed interface TransferMutation {
    data object Idle : TransferMutation
    data class Running(val action: TransferAction, val requestId: TransfersRequestId) : TransferMutation
    data class Failed(val action: TransferAction, val failure: FilesFailure) : TransferMutation
}

sealed interface TransferNavigation {
    data object Idle : TransferNavigation
    data class Resolving(
        val fileId: TransferFileId,
        val requestId: TransfersRequestId,
    ) : TransferNavigation
    data class Failed(val failure: FilesFailure) : TransferNavigation
}

sealed interface TransferNotice {
    val requestId: TransfersRequestId

    data class FilePreparing(
        val transferId: TransferId,
        override val requestId: TransfersRequestId,
    ) : TransferNotice
    data class FileUnavailable(
        val transferId: TransferId,
        override val requestId: TransfersRequestId,
    ) : TransferNotice
}

@ConsistentCopyVisibility
data class TransfersState internal constructor(
    val content: TransfersContent,
    val refresh: TransfersRefresh = TransfersRefresh.Idle,
    val mutation: TransferMutation = TransferMutation.Idle,
    val navigation: TransferNavigation = TransferNavigation.Idle,
    val notice: TransferNotice? = null,
    val visible: Boolean = false,
    internal val lastSuccessfulAddRequestId: TransfersRequestId? = null,
    internal val firstPageIds: Set<TransferId> = emptySet(),
    internal val consumedCursors: Set<TransferCursor> = emptySet(),
    internal val nextRequestValue: Long = 1L,
)

sealed interface TransfersEvent {
    data object LoadNextPage : TransfersEvent
    data object RetryLoad : TransfersEvent
    data object Refresh : TransfersEvent
    data object Poll : TransfersEvent
    data class VisibilityChanged(val visible: Boolean) : TransfersEvent
    data class Add(val input: String) : TransfersEvent
    data class Cancel(val id: TransferId) : TransfersEvent
    data class RetryTransfer(val id: TransferId) : TransfersEvent
    data object CleanCompleted : TransfersEvent
    data object DismissMutationFailure : TransfersEvent
    data class Open(val id: TransferId) : TransfersEvent
    data class OpenSucceeded(val requestId: TransfersRequestId) : TransfersEvent
    data class OpenFailed(val requestId: TransfersRequestId, val failure: FilesFailure) : TransfersEvent
    data object DismissNavigationFailure : TransfersEvent
    data class DismissNotice(val requestId: TransfersRequestId) : TransfersEvent
    data class ListSucceeded(val requestId: TransfersRequestId, val page: TransfersPage) : TransfersEvent
    data class FirstPageRefreshed(
        val requestId: TransfersRequestId,
        val page: TransfersPage,
        val reconciledItems: List<TransferItem>,
    ) : TransfersEvent
    data class RowsRefreshed(
        val requestId: TransfersRequestId,
        val items: List<TransferItem>,
        val missingIds: Set<TransferId>,
    ) : TransfersEvent
    data class ListFailed(val requestId: TransfersRequestId, val failure: FilesFailure) : TransfersEvent
    data class MutationSucceeded(
        val requestId: TransfersRequestId,
        val item: TransferItem? = null,
        val affectedIds: Set<TransferId> = emptySet(),
    ) : TransfersEvent
    data class MutationFailed(val requestId: TransfersRequestId, val failure: FilesFailure) : TransfersEvent
}

sealed interface TransfersEffect {
    data class Load(
        val cursor: TransferCursor?,
        val requestId: TransfersRequestId,
        val reconcileIds: Set<TransferId> = emptySet(),
    ) : TransfersEffect
    data class RefreshRows(
        val ids: List<TransferId>,
        val requestId: TransfersRequestId,
    ) : TransfersEffect
    data class Mutate(val action: TransferAction, val requestId: TransfersRequestId) : TransfersEffect
}

data class TransfersTransition(
    val state: TransfersState,
    val effect: TransfersEffect? = null,
    val consumed: Boolean = true,
)

object TransfersReducer {
    fun start(): TransfersTransition {
        val requestId = TransfersRequestId(1L)
        return TransfersTransition(
            TransfersState(content = TransfersContent.InitialLoading(requestId), nextRequestValue = 2L),
            TransfersEffect.Load(null, requestId),
        )
    }

    fun reduce(state: TransfersState, event: TransfersEvent): TransfersTransition =
        when (event) {
            TransfersEvent.LoadNextPage -> state.loadNextPage()
            TransfersEvent.RetryLoad -> state.retryLoad()
            TransfersEvent.Refresh -> state.refresh()
            TransfersEvent.Poll -> state.poll()
            is TransfersEvent.VisibilityChanged -> state.visibilityChanged(event.visible)
            is TransfersEvent.Add -> state.add(event.input)
            is TransfersEvent.Cancel -> state.cancel(event.id)
            is TransfersEvent.RetryTransfer -> state.retryTransfer(event.id)
            TransfersEvent.CleanCompleted -> state.cleanCompleted()
            TransfersEvent.DismissMutationFailure -> state.dismissMutationFailure()
            is TransfersEvent.Open,
            is TransfersEvent.OpenSucceeded,
            is TransfersEvent.OpenFailed,
            TransfersEvent.DismissNavigationFailure,
            is TransfersEvent.DismissNotice,
            -> reduceNavigation(state, event)
            else -> reduceResult(state, event)
        }

    private fun reduceNavigation(state: TransfersState, event: TransfersEvent): TransfersTransition =
        when (event) {
            is TransfersEvent.Open -> state.open(event.id)
            is TransfersEvent.OpenSucceeded -> state.openSucceeded(event.requestId)
            is TransfersEvent.OpenFailed -> state.openFailed(event.requestId, event.failure)
            TransfersEvent.DismissNavigationFailure -> state.dismissNavigationFailure()
            is TransfersEvent.DismissNotice -> state.dismissNotice(event.requestId)
            else -> error("Only navigation events reach reduceNavigation")
        }

    private fun reduceResult(state: TransfersState, event: TransfersEvent): TransfersTransition =
        when (event) {
            is TransfersEvent.ListSucceeded -> state.listSucceeded(event)
            is TransfersEvent.FirstPageRefreshed -> {
                val refreshing = state.refresh as? TransfersRefresh.Refreshing
                if (refreshing?.requestId == event.requestId) {
                    state.refreshSucceeded(event.page, event.reconciledItems)
                } else {
                    TransfersTransition(state, consumed = false)
                }
            }
            is TransfersEvent.RowsRefreshed -> state.rowsRefreshed(event)
            is TransfersEvent.ListFailed -> state.listFailed(event)
            is TransfersEvent.MutationSucceeded -> state.mutationSucceeded(event)
            is TransfersEvent.MutationFailed -> state.mutationFailed(event)
            else -> error("Only asynchronous results reach reduceResult")
        }
}

private fun TransfersState.visibilityChanged(visible: Boolean): TransfersTransition {
    val next =
        copy(
            visible = visible,
            refresh =
                if (!visible && refresh.isRunning) {
                    TransfersRefresh.Idle
                } else {
                    refresh
                },
        )
    return TransfersTransition(next, consumed = next != this)
}
