package io.putdotio.android.transfers

internal fun TransfersState.hasRowsNeedingPolling(): Boolean =
    (content as? TransfersContent.Ready)?.items?.any(TransferItem::needsPolling) == true

internal fun TransfersState.poll(): TransfersTransition {
    val ready = content as? TransfersContent.Ready
    val ids = ready?.items?.filter(TransferItem::needsPolling)?.map(TransferItem::id).orEmpty()
    val busy =
            !visible ||
            ids.isEmpty() ||
            refresh != TransfersRefresh.Idle ||
            mutation is TransferMutation.Running ||
            navigation is TransferNavigation.Resolving ||
            content.isPaging
    if (busy) return TransfersTransition(this, consumed = false)

    val requestId = TransfersRequestId(nextRequestValue)
    return TransfersTransition(
        copy(
            refresh = TransfersRefresh.Polling(requestId),
            nextRequestValue = nextRequestValue + 1,
        ),
        TransfersEffect.RefreshRows(ids, requestId),
    )
}

internal fun TransfersState.rowsRefreshed(event: TransfersEvent.RowsRefreshed): TransfersTransition {
    val polling = refresh as? TransfersRefresh.Polling
    val ready = content as? TransfersContent.Ready
    if (polling?.requestId != event.requestId || ready == null) {
        return TransfersTransition(this, consumed = false)
    }
    val refreshedById = event.items.associateBy(TransferItem::id)
    val items =
        ready.items
            .filterNot { it.id in event.missingIds }
            .map { refreshedById[it.id] ?: it }
    return if (items.isEmpty() && ready.paging != TransfersPaging.Complete) {
        val reloadRequestId = TransfersRequestId(nextRequestValue)
        TransfersTransition(
            copy(
                content = TransfersContent.InitialLoading(reloadRequestId),
                refresh = TransfersRefresh.Idle,
                firstPageIds = emptySet(),
                consumedCursors = emptySet(),
                nextRequestValue = nextRequestValue + 1,
            ),
            TransfersEffect.Load(null, reloadRequestId),
        )
    } else {
        TransfersTransition(
            copy(
                content = if (items.isEmpty()) TransfersContent.Empty else ready.copy(items = items),
                refresh = TransfersRefresh.Idle,
                firstPageIds = firstPageIds - event.missingIds,
            ),
        )
    }
}

private fun TransferItem.needsPolling(): Boolean =
    !status.isTerminal ||
        (status == AppTransferStatus.Completed && fileId == null && userFileExists != false)

internal val TransfersContent.isPaging: Boolean
    get() = (this as? TransfersContent.Ready)?.paging is TransfersPaging.Loading
