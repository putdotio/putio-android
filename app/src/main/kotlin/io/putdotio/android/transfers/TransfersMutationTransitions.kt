package io.putdotio.android.transfers

internal fun TransfersState.add(input: String): TransfersTransition {
    val submission = TransferSubmission.parse(input)
    return if (submission == null) {
        TransfersTransition(this, consumed = false)
    } else {
        mutate(TransferAction.Add(submission))
    }
}

internal fun TransfersState.cancel(id: TransferId): TransfersTransition {
    val item = content.items().firstOrNull { it.id == id }
    return if (item == null || !item.status.canCancel) {
        TransfersTransition(this, consumed = false)
    } else {
        mutate(TransferAction.Cancel(id))
    }
}

internal fun TransfersState.retryTransfer(id: TransferId): TransfersTransition {
    val item = content.items().firstOrNull { it.id == id }
    return if (item?.status != AppTransferStatus.Failed) {
        TransfersTransition(this, consumed = false)
    } else {
        mutate(TransferAction.Retry(id))
    }
}

internal fun TransfersState.cleanCompleted(): TransfersTransition {
    return if (content !is TransfersContent.Ready) {
        TransfersTransition(this, consumed = false)
    } else {
        mutate(TransferAction.Clean)
    }
}

internal fun TransfersState.mutate(action: TransferAction): TransfersTransition {
    if (
        mutation is TransferMutation.Running ||
        navigation is TransferNavigation.Resolving ||
        content is TransfersContent.InitialLoading
    ) {
        return TransfersTransition(this, consumed = false)
    }
    val requestId = TransfersRequestId(nextRequestValue)
    val base = withoutActiveRead()
    return TransfersTransition(
        base.copy(
            mutation = TransferMutation.Running(action, requestId),
            nextRequestValue = nextRequestValue + 1,
        ),
        TransfersEffect.Mutate(action, requestId),
    )
}

internal fun TransfersState.dismissMutationFailure(): TransfersTransition =
    if (mutation is TransferMutation.Failed) {
        TransfersTransition(copy(mutation = TransferMutation.Idle))
    } else {
        TransfersTransition(this, consumed = false)
    }

internal fun TransfersState.mutationSucceeded(
    event: TransfersEvent.MutationSucceeded,
): TransfersTransition {
    val running = mutation as? TransferMutation.Running
    return if (running?.requestId != event.requestId) {
        TransfersTransition(this, consumed = false)
    } else {
        val items = applyMutationSuccess(content.items(), running.action, event)
        val updatedFirstPageIds = updatedFirstPageIds(running.action, event, items)
        val successful =
            copy(
                content = content.withItems(items),
                mutation = TransferMutation.Idle,
                lastSuccessfulAddRequestId =
                    if (running.action is TransferAction.Add) event.requestId else lastSuccessfulAddRequestId,
                firstPageIds = updatedFirstPageIds,
            )
        if (content is TransfersContent.Failed && running.action is TransferAction.Add) {
            val reloadRequestId = TransfersRequestId(nextRequestValue)
            return TransfersTransition(
                successful.copy(
                    content = TransfersContent.InitialLoading(reloadRequestId),
                    firstPageIds = emptySet(),
                    consumedCursors = emptySet(),
                    nextRequestValue = nextRequestValue + 1,
                ),
                TransfersEffect.Load(null, reloadRequestId),
            )
        }
        val hadMorePages =
            (content as? TransfersContent.Ready)?.paging?.let { it != TransfersPaging.Complete } == true
        if (items.isEmpty() && hadMorePages) {
            val reloadRequestId = TransfersRequestId(nextRequestValue)
            TransfersTransition(
                successful.copy(
                    content = TransfersContent.InitialLoading(reloadRequestId),
                    firstPageIds = emptySet(),
                    consumedCursors = emptySet(),
                    nextRequestValue = nextRequestValue + 1,
                ),
                TransfersEffect.Load(null, reloadRequestId),
            )
        } else {
            TransfersTransition(successful)
        }
    }
}

private fun applyMutationSuccess(
    items: List<TransferItem>,
    action: TransferAction,
    event: TransfersEvent.MutationSucceeded,
): List<TransferItem> =
    when (action) {
        is TransferAction.Add -> listOfNotNull(event.item) + items.filterNot { it.id == event.item?.id }
        is TransferAction.Cancel -> items.filterNot { it.id == action.id }
        is TransferAction.Retry -> items.map { if (it.id == action.id) event.item ?: it else it }
        TransferAction.Clean ->
            items.filterNot {
                it.status == AppTransferStatus.Completed || it.id in event.affectedIds
            }
    }

private fun TransfersState.withoutActiveRead(): TransfersState {
    val normalizedContent =
        when (val current = content) {
            is TransfersContent.Ready ->
                when (val paging = current.paging) {
                    is TransfersPaging.Loading -> current.copy(paging = TransfersPaging.Available(paging.cursor))
                    else -> current
                }
            else -> current
        }
    return copy(
        content = normalizedContent,
        refresh = if (refresh.isRunning) TransfersRefresh.Idle else refresh,
    )
}

internal fun TransfersState.mutationFailed(event: TransfersEvent.MutationFailed): TransfersTransition {
    val running = mutation as? TransferMutation.Running
    return if (running?.requestId == event.requestId) {
        TransfersTransition(copy(mutation = TransferMutation.Failed(running.action, event.failure)))
    } else {
        TransfersTransition(this, consumed = false)
    }
}
