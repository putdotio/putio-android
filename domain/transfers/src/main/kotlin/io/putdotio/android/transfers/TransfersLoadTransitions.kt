package io.putdotio.android.transfers

import io.putdotio.android.files.FilesFailure

internal fun TransfersState.loadNextPage(): TransfersTransition {
    val ready = content as? TransfersContent.Ready
    val available = ready?.paging as? TransfersPaging.Available
    val busy =
        refresh.isRunning ||
            mutation is TransferMutation.Running ||
            navigation is TransferNavigation.Resolving
    return when {
        ready == null || available == null -> TransfersTransition(this, consumed = false)
        busy -> TransfersTransition(this, consumed = false)
        available.cursor in consumedCursors ->
            TransfersTransition(copy(content = ready.copy(paging = TransfersPaging.Complete)))
        else -> {
            val requestId = TransfersRequestId(nextRequestValue)
            TransfersTransition(
                copy(
                    content = ready.copy(paging = TransfersPaging.Loading(available.cursor, requestId)),
                    nextRequestValue = nextRequestValue + 1,
                ),
                TransfersEffect.Load(available.cursor, requestId),
            )
        }
    }
}

internal fun TransfersState.retryLoad(): TransfersTransition =
    if (mutation is TransferMutation.Running || navigation is TransferNavigation.Resolving) {
        TransfersTransition(this, consumed = false)
    } else {
        when (val current = content) {
            is TransfersContent.Failed -> startInitialLoad()
            is TransfersContent.Ready -> retryPage(current)
            else -> TransfersTransition(this, consumed = false)
        }
    }

private fun TransfersState.retryPage(ready: TransfersContent.Ready): TransfersTransition {
    val failed = ready.paging as? TransfersPaging.Failed
    val busy =
        refresh.isRunning ||
            mutation is TransferMutation.Running ||
            navigation is TransferNavigation.Resolving
    return if (failed == null || busy) {
        TransfersTransition(this, consumed = false)
    } else {
        val requestId = TransfersRequestId(nextRequestValue)
        TransfersTransition(
            copy(
                content = ready.copy(paging = TransfersPaging.Loading(failed.cursor, requestId)),
                nextRequestValue = nextRequestValue + 1,
            ),
            TransfersEffect.Load(failed.cursor, requestId),
        )
    }
}

private fun TransfersState.startInitialLoad(): TransfersTransition {
    val requestId = TransfersRequestId(nextRequestValue)
    return TransfersTransition(
        copy(
            content = TransfersContent.InitialLoading(requestId),
            firstPageIds = emptySet(),
            consumedCursors = emptySet(),
            nextRequestValue = nextRequestValue + 1,
        ),
        TransfersEffect.Load(null, requestId),
    )
}

internal fun TransfersState.refresh(): TransfersTransition {
    val canRefresh =
        (content is TransfersContent.Empty || content is TransfersContent.Ready) &&
            !refresh.isRunning &&
            mutation !is TransferMutation.Running &&
            navigation !is TransferNavigation.Resolving &&
            !content.isPaging
    return if (canRefresh) {
        val requestId = TransfersRequestId(nextRequestValue)
        TransfersTransition(
            copy(
                refresh = TransfersRefresh.Refreshing(requestId),
                nextRequestValue = nextRequestValue + 1,
            ),
            TransfersEffect.Load(
                cursor = null,
                requestId = requestId,
                reconcileIds =
                    content.items()
                        .mapTo(mutableSetOf(), TransferItem::id)
                        .takeIf { hasRowsBeyondFirstPage }
                        .orEmpty(),
            ),
        )
    } else {
        TransfersTransition(this, consumed = false)
    }
}

internal fun TransfersState.listSucceeded(event: TransfersEvent.ListSucceeded): TransfersTransition {
    val initial = content as? TransfersContent.InitialLoading
    val refreshing = refresh as? TransfersRefresh.Refreshing
    val ready = content as? TransfersContent.Ready
    val paging = ready?.paging as? TransfersPaging.Loading
    return when {
        initial?.requestId == event.requestId -> initialSucceeded(event.page)
        refreshing?.requestId == event.requestId -> refreshSucceeded(event.page)
        ready != null && paging?.requestId == event.requestId -> pageSucceeded(ready, paging, event.page)
        else -> TransfersTransition(this, consumed = false)
    }
}

private fun TransfersState.initialSucceeded(page: TransfersPage): TransfersTransition =
    TransfersTransition(
        copy(
            content = page.toContent(),
            firstPageIds = page.items.mapTo(mutableSetOf(), TransferItem::id),
            consumedCursors = emptySet(),
        ),
    )

internal fun TransfersState.refreshSucceeded(
    page: TransfersPage,
    reconciledItems: List<TransferItem> = emptyList(),
): TransfersTransition {
    val refreshedFirstPageIds = page.items.mapTo(mutableSetOf(), TransferItem::id)
    return (content as? TransfersContent.Ready)
        ?.takeIf { hasRowsBeyondFirstPage }
        ?.let { ready ->
            val items = page.items + reconciledItems
            if (items.isEmpty()) {
                TransfersTransition(
                    copy(
                        content = TransfersContent.Empty,
                        refresh = TransfersRefresh.Idle,
                        firstPageIds = emptySet(),
                        consumedCursors = emptySet(),
                    ),
                )
            } else {
                val paging = page.nextCursor?.let(TransfersPaging::Available) ?: TransfersPaging.Complete
                TransfersTransition(
                    copy(
                        content = TransfersContent.Ready(items.distinctBy(TransferItem::id), paging),
                        refresh = TransfersRefresh.Idle,
                        firstPageIds = refreshedFirstPageIds,
                        consumedCursors = emptySet(),
                    ),
                )
            }
        }
        ?: TransfersTransition(
            copy(
                content = page.toContent(),
                refresh = TransfersRefresh.Idle,
                firstPageIds = refreshedFirstPageIds,
                consumedCursors = emptySet(),
            ),
        )
}

private val TransfersState.hasRowsBeyondFirstPage: Boolean
    get() = content.items().any { it.id !in firstPageIds }

private fun TransfersState.pageSucceeded(
    ready: TransfersContent.Ready,
    paging: TransfersPaging.Loading,
    page: TransfersPage,
): TransfersTransition {
    val items = (ready.items + page.items).distinctBy(TransferItem::id)
    val consumed = consumedCursors + paging.cursor
    val nextCursor = page.nextCursor?.takeUnless(consumed::contains)
    val nextPaging = nextCursor?.let(TransfersPaging::Available) ?: TransfersPaging.Complete
    return TransfersTransition(
        copy(
            content = TransfersContent.Ready(items, nextPaging),
            consumedCursors = consumed,
        ),
    )
}

internal fun TransfersState.listFailed(event: TransfersEvent.ListFailed): TransfersTransition {
    val initial = content as? TransfersContent.InitialLoading
    val refreshing = refresh as? TransfersRefresh.Refreshing
    val polling = refresh as? TransfersRefresh.Polling
    val ready = content as? TransfersContent.Ready
    val paging = ready?.paging as? TransfersPaging.Loading
    return when {
        initial?.requestId == event.requestId ->
            TransfersTransition(copy(content = TransfersContent.Failed(event.failure)))
        refreshing?.requestId == event.requestId ->
            TransfersTransition(copy(refresh = TransfersRefresh.Failed(event.failure)))
        polling?.requestId == event.requestId ->
            TransfersTransition(
                copy(
                    refresh =
                        if (event.failure is FilesFailure.AuthenticationRequired) {
                            TransfersRefresh.Failed(event.failure)
                        } else {
                            TransfersRefresh.Idle
                        },
                ),
            )
        ready != null && paging?.requestId == event.requestId ->
            TransfersTransition(
                copy(content = ready.copy(paging = TransfersPaging.Failed(paging.cursor, event.failure))),
            )
        else -> TransfersTransition(this, consumed = false)
    }
}
