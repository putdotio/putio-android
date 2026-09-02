package io.putdotio.android.history

internal fun HistoryState.setEnabled(enabled: Boolean): HistoryTransition {
    val currentlyEnabled = content !is HistoryContent.Disabled
    return when {
        enabled == currentlyEnabled -> HistoryTransition(this, consumed = false)
        !enabled ->
            HistoryTransition(
                copy(
                    content = HistoryContent.Disabled,
                    clearing = HistoryClearing.Idle,
                    consumedBefore = emptySet(),
                ),
            )
        else -> {
            val requestId = HistoryRequestId(nextRequestValue)
            HistoryTransition(
                state =
                    copy(
                        content = HistoryContent.Loading(requestId),
                        clearing = HistoryClearing.Idle,
                        consumedBefore = emptySet(),
                        nextRequestValue = requestId.value + 1,
                    ),
                effect = HistoryEffect.Load(before = null, requestId),
            )
        }
    }
}

internal fun HistoryState.loadNextPage(): HistoryTransition {
    val ready = content as? HistoryContent.Ready
    val paging = ready?.paging as? HistoryPaging.Available
    return if (ready == null || paging == null) {
        HistoryTransition(this, consumed = false)
    } else {
        loadingPage(ready, paging.before)
    }
}

internal fun HistoryState.retry(): HistoryTransition =
    when (val current = content) {
        is HistoryContent.Failed -> reloadInitial()
        is HistoryContent.Ready -> {
            val failed = current.paging as? HistoryPaging.Failed
            if (failed == null) HistoryTransition(this, consumed = false) else loadingPage(current, failed.before)
        }
        else -> HistoryTransition(this, consumed = false)
    }

private fun HistoryState.loadingPage(
    ready: HistoryContent.Ready,
    before: HistoryEventId,
): HistoryTransition {
    val requestId = HistoryRequestId(nextRequestValue)
    return HistoryTransition(
        state =
            copy(
                content = ready.copy(paging = HistoryPaging.Loading(before, requestId)),
                nextRequestValue = nextRequestValue + 1,
            ),
        effect = HistoryEffect.Load(before, requestId),
    )
}

private fun HistoryState.reloadInitial(): HistoryTransition {
    val requestId = HistoryRequestId(nextRequestValue)
    return HistoryTransition(
        copy(content = HistoryContent.Loading(requestId), nextRequestValue = nextRequestValue + 1),
        HistoryEffect.Load(null, requestId),
    )
}

internal fun HistoryState.loadSucceeded(event: HistoryEvent.LoadSucceeded): HistoryTransition {
    val initial = content as? HistoryContent.Loading
    val ready = content as? HistoryContent.Ready
    val loading = ready?.paging as? HistoryPaging.Loading
    return when {
        initial?.requestId == event.requestId -> initialPageSucceeded(event.page)
        ready != null && loading?.requestId == event.requestId -> pageSucceeded(ready, loading, event.page)
        else -> HistoryTransition(this, consumed = false)
    }
}

private fun HistoryState.initialPageSucceeded(page: HistoryPage): HistoryTransition {
    val unique = page.items.distinctBy(HistoryItem::id)
    return HistoryTransition(copy(content = unique.toContent(page.hasMore), consumedBefore = emptySet()))
}

private fun HistoryState.pageSucceeded(
    ready: HistoryContent.Ready,
    loading: HistoryPaging.Loading,
    page: HistoryPage,
): HistoryTransition {
    val items = (ready.items + page.items).distinctBy(HistoryItem::id)
    val consumed = consumedBefore + loading.before
    return HistoryTransition(
        copy(content = items.toContent(page.hasMore, consumed), consumedBefore = consumed),
    )
}

private fun List<HistoryItem>.toContent(
    hasMore: Boolean,
    consumed: Set<HistoryEventId> = emptySet(),
): HistoryContent =
    if (isEmpty()) {
        HistoryContent.Empty
    } else {
        val before = last().id
        val paging =
            if (hasMore && before !in consumed) HistoryPaging.Available(before) else HistoryPaging.Complete
        HistoryContent.Ready(this, paging)
    }

internal fun HistoryState.loadFailed(event: HistoryEvent.LoadFailed): HistoryTransition {
    val initial = content as? HistoryContent.Loading
    val ready = content as? HistoryContent.Ready
    val loading = ready?.paging as? HistoryPaging.Loading
    return when {
        initial?.requestId == event.requestId ->
            HistoryTransition(copy(content = HistoryContent.Failed(event.failure)))
        ready != null && loading?.requestId == event.requestId ->
            HistoryTransition(
                copy(content = ready.copy(paging = HistoryPaging.Failed(loading.before, event.failure))),
            )
        else -> HistoryTransition(this, consumed = false)
    }
}
