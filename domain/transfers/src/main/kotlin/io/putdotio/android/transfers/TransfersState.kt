package io.putdotio.android.transfers

import io.putdotio.android.FilesFailure
import io.putdotio.android.PutioFailure

@JvmInline
public value class TransfersRequestId(internal val value: Long)

public sealed interface TransfersPaging {
    public data object Complete : TransfersPaging
    public data class Available(val cursor: TransferCursor) : TransfersPaging
    public data class Loading(val cursor: TransferCursor, val requestId: TransfersRequestId) : TransfersPaging
    public data class Failed(val cursor: TransferCursor, val failure: PutioFailure) : TransfersPaging
}

public sealed interface TransfersContent {
    public data class InitialLoading(val requestId: TransfersRequestId) : TransfersContent
    public data object Empty : TransfersContent
    public data class Ready(val items: List<TransferItem>, val paging: TransfersPaging) : TransfersContent {
        init { require(items.isNotEmpty()) }
    }
    public data class Failed(val failure: PutioFailure) : TransfersContent
}

public sealed interface TransfersRefresh {
    public data object Idle : TransfersRefresh
    public data class Refreshing(val requestId: TransfersRequestId) : TransfersRefresh
    public data class Polling(val requestId: TransfersRequestId) : TransfersRefresh
    public data class Failed(val failure: PutioFailure) : TransfersRefresh
}

public val TransfersRefresh.isRunning: Boolean
    get() = this is TransfersRefresh.Refreshing || this is TransfersRefresh.Polling

internal fun TransfersRefresh.hasRequest(requestId: TransfersRequestId): Boolean =
    when (this) {
        is TransfersRefresh.Refreshing -> this.requestId == requestId
        is TransfersRefresh.Polling -> this.requestId == requestId
        TransfersRefresh.Idle,
        is TransfersRefresh.Failed,
        -> false
    }

public sealed interface TransferAction {
    public data class Add(val request: TransferAddRequest) : TransferAction
    public data class Cancel(val id: TransferId) : TransferAction
    public data class Retry(val id: TransferId) : TransferAction
    public data object Clean : TransferAction
}

public sealed interface TransferMutation {
    public data object Idle : TransferMutation
    public data class Running(val action: TransferAction, val requestId: TransfersRequestId) : TransferMutation
    public data class Failed(val action: TransferAction, val failure: PutioFailure) : TransferMutation
}

public sealed interface TransferNavigation {
    public data object Idle : TransferNavigation
    public data class Resolving(
        val fileId: TransferFileId,
        val requestId: TransfersRequestId,
    ) : TransferNavigation
    public data class Failed(val failure: FilesFailure) : TransferNavigation
}

public sealed interface TransferNotice {
    public val requestId: TransfersRequestId

    public data class FilePreparing(
        val transferId: TransferId,
        override val requestId: TransfersRequestId,
    ) : TransferNotice
    public data class FileUnavailable(
        val transferId: TransferId,
        override val requestId: TransfersRequestId,
    ) : TransferNotice
}

/** The last retry's result, held until the screen has reported it. */
public sealed interface TransferRetryOutcome {
    public val requestId: TransfersRequestId

    public data class Accepted(override val requestId: TransfersRequestId) : TransferRetryOutcome
    public data class Failed(
        override val requestId: TransfersRequestId,
        val failure: PutioFailure,
    ) : TransferRetryOutcome
}

@ConsistentCopyVisibility
public data class TransfersState internal constructor(
    val content: TransfersContent,
    val refresh: TransfersRefresh = TransfersRefresh.Idle,
    val mutation: TransferMutation = TransferMutation.Idle,
    val navigation: TransferNavigation = TransferNavigation.Idle,
    val notice: TransferNotice? = null,
    val retryOutcome: TransferRetryOutcome? = null,
    val visible: Boolean = false,
    val lastAddReceipt: TransferAddReceipt? = null,
    internal val firstPageIds: Set<TransferId> = emptySet(),
    internal val consumedCursors: Set<TransferCursor> = emptySet(),
    internal val nextRequestValue: Long = 1L,
) {
    val lastSuccessfulAddRequestId: TransfersRequestId? get() = lastAddReceipt?.requestId
}

/** The last accepted add: how many transfers put.io started and which links it refused. */
public data class TransferAddReceipt(
    val requestId: TransfersRequestId,
    val addedCount: Int,
    val rejectedLinks: List<String>,
) {
    override fun toString(): String =
        "TransferAddReceipt(requestId=$requestId, addedCount=$addedCount, rejected=${rejectedLinks.size})"
}

public sealed interface TransfersEvent {
    public data object LoadNextPage : TransfersEvent
    public data object RetryLoad : TransfersEvent
    public data object Refresh : TransfersEvent
    public data object Poll : TransfersEvent
    public data class VisibilityChanged(val visible: Boolean) : TransfersEvent
    /** Whitespace-separated links; [saveParentId] null saves to the account's default download folder. */
    public data class Add(val input: String, val saveParentId: Long? = null) : TransfersEvent {
        override fun toString(): String = "Add(<redacted>, saveParentId=$saveParentId)"
    }
    public data class AddTorrent(val file: TorrentUpload, val saveParentId: Long? = null) : TransfersEvent
    public data class Cancel(val id: TransferId) : TransfersEvent
    public data class RetryTransfer(val id: TransferId) : TransfersEvent
    public data object CleanCompleted : TransfersEvent
    public data object DismissMutationFailure : TransfersEvent
    public data class DismissRetryOutcome(val requestId: TransfersRequestId) : TransfersEvent
    public data class Open(val id: TransferId) : TransfersEvent
    public data class OpenSucceeded(val requestId: TransfersRequestId) : TransfersEvent
    public data class OpenFailed(val requestId: TransfersRequestId, val failure: FilesFailure) : TransfersEvent
    public data object DismissNavigationFailure : TransfersEvent
    public data class DismissNotice(val requestId: TransfersRequestId) : TransfersEvent
    public data class ListSucceeded(val requestId: TransfersRequestId, val page: TransfersPage) : TransfersEvent
    public data class FirstPageRefreshed(
        val requestId: TransfersRequestId,
        val page: TransfersPage,
        val reconciledItems: List<TransferItem>,
    ) : TransfersEvent
    public data class RowsRefreshed(
        val requestId: TransfersRequestId,
        val items: List<TransferItem>,
        val missingIds: Set<TransferId>,
    ) : TransfersEvent
    public data class ListFailed(val requestId: TransfersRequestId, val failure: PutioFailure) : TransfersEvent
    public data class MutationSucceeded(
        val requestId: TransfersRequestId,
        val item: TransferItem? = null,
        val affectedIds: Set<TransferId> = emptySet(),
        val added: TransferAddOutcome? = null,
    ) : TransfersEvent
    public data class MutationFailed(val requestId: TransfersRequestId, val failure: PutioFailure) : TransfersEvent
}

public sealed interface TransfersEffect {
    public data class Load(
        val cursor: TransferCursor?,
        val requestId: TransfersRequestId,
        val reconcileIds: Set<TransferId> = emptySet(),
    ) : TransfersEffect
    public data class RefreshRows(
        val ids: List<TransferId>,
        val requestId: TransfersRequestId,
    ) : TransfersEffect
    public data class Mutate(val action: TransferAction, val requestId: TransfersRequestId) : TransfersEffect
}

public data class TransfersTransition(
    val state: TransfersState,
    val effect: TransfersEffect? = null,
    val consumed: Boolean = true,
)

public object TransfersReducer {
    public fun start(): TransfersTransition {
        val requestId = TransfersRequestId(1L)
        return TransfersTransition(
            TransfersState(content = TransfersContent.InitialLoading(requestId), nextRequestValue = 2L),
            TransfersEffect.Load(null, requestId),
        )
    }

    public fun reduce(state: TransfersState, event: TransfersEvent): TransfersTransition =
        when (event) {
            TransfersEvent.LoadNextPage -> state.loadNextPage()
            TransfersEvent.RetryLoad -> state.retryLoad()
            TransfersEvent.Refresh -> state.refresh()
            TransfersEvent.Poll -> state.poll()
            is TransfersEvent.VisibilityChanged -> state.visibilityChanged(event.visible)
            is TransfersEvent.Add, is TransfersEvent.AddTorrent -> state.add(event)
            is TransfersEvent.Cancel -> state.cancel(event.id)
            is TransfersEvent.RetryTransfer -> state.retryTransfer(event.id)
            TransfersEvent.CleanCompleted -> state.cleanCompleted()
            TransfersEvent.DismissMutationFailure -> state.dismissMutationFailure()
            is TransfersEvent.DismissRetryOutcome -> state.dismissRetryOutcome(event.requestId)
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

private fun TransfersState.dismissRetryOutcome(requestId: TransfersRequestId): TransfersTransition =
    if (retryOutcome?.requestId == requestId) {
        TransfersTransition(copy(retryOutcome = null))
    } else {
        TransfersTransition(this, consumed = false)
    }
