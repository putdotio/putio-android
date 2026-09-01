package io.putdotio.android.transfers

internal fun TransfersContent.items(): List<TransferItem> =
    (this as? TransfersContent.Ready)?.items.orEmpty()

internal fun TransfersContent.withItems(items: List<TransferItem>): TransfersContent =
    if (items.isEmpty()) {
        TransfersContent.Empty
    } else {
        val paging = (this as? TransfersContent.Ready)?.paging ?: TransfersPaging.Complete
        TransfersContent.Ready(items.distinctBy(TransferItem::id), paging)
    }

internal fun TransfersPage.toContent(): TransfersContent {
    val unique = items.distinctBy(TransferItem::id)
    return if (unique.isEmpty()) {
        TransfersContent.Empty
    } else {
        TransfersContent.Ready(
            unique,
            nextCursor?.let(TransfersPaging::Available) ?: TransfersPaging.Complete,
        )
    }
}

internal fun TransfersState.updatedFirstPageIds(
    action: TransferAction,
    event: TransfersEvent.MutationSucceeded,
    items: List<TransferItem>,
): Set<TransferId> =
    when (action) {
        is TransferAction.Add -> event.item?.id?.let { firstPageIds + it } ?: firstPageIds
        is TransferAction.Cancel -> firstPageIds - action.id
        is TransferAction.Retry ->
            if (action.id in firstPageIds) {
                (firstPageIds - action.id) + listOfNotNull(event.item?.id)
            } else {
                firstPageIds
            }
        TransferAction.Clean -> firstPageIds.intersect(items.mapTo(mutableSetOf(), TransferItem::id))
    }
