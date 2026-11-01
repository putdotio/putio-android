package io.putdotio.android.history

internal fun HistoryState.refresh(): HistoryTransition {
    val current = content
    val listed = current is HistoryContent.Ready || current == HistoryContent.Empty
    return when {
        current is HistoryContent.Failed -> reloadInitial()
        !listed || clearing != HistoryClearing.Idle || refreshing != null -> HistoryTransition(this, consumed = false)
        else -> {
            val requestId = HistoryRequestId(nextRequestValue)
            HistoryTransition(
                copy(refreshing = requestId, nextRequestValue = nextRequestValue + 1),
                HistoryEffect.Load(null, requestId),
            )
        }
    }
}

internal fun HistoryState.refreshSucceeded(ready: HistoryContent.Ready?, page: HistoryPage): HistoryTransition {
    val fresh = page.items.distinctBy(HistoryItem::id)
    val oldest = fresh.lastOrNull()?.id?.value
    val freshIds = fresh.mapTo(HashSet(), HistoryItem::id)
    val older = if (ready == null || oldest == null || !page.hasMore) {
        emptyList()
    } else {
        ready.items.filter { it.id.value < oldest && it.id !in freshIds }
    }
    val refreshed = if (older.isEmpty() || ready == null) {
        copy(content = fresh.toContent(page.hasMore), consumedBefore = emptySet())
    } else {
        copy(content = ready.copy(items = fresh + older))
    }
    return HistoryTransition(refreshed.copy(refreshing = null))
}
