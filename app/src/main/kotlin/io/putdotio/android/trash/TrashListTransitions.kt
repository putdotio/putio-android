package io.putdotio.android.trash

internal fun TrashMachine.read(event: TrashEvent.ReadEvent): TrashMachine? = when (event) {
    TrashEvent.Open -> if (opened) null else startList()
    TrashEvent.Refresh -> when (request) {
        null, is TrashRequest.ListPage -> startList()
        else -> null
    }
    TrashEvent.Retry -> retryList()
    TrashEvent.LoadNextPage -> startNextPage()
}

internal fun TrashMachine.startList(verifiesAction: Boolean = false): TrashMachine {
    val content = (state.content as? TrashContent.Loaded)?.copy(
        isRefreshing = true, isLoadingMore = false, refreshFailure = null, pageFailure = null,
    ) ?: TrashContent.Loading
    return copy(
        state = state.copy(
            content = content, confirmation = null, confirmationId = null,
            actionConfirmation = null, actionConfirmationId = null,
        ),
        request = TrashRequest.ListPage(nextRequestId, verifiesAction = verifiesAction),
        nextRequestId = nextRequestId + 1L, opened = true,
    )
}

private fun TrashMachine.retryList(): TrashMachine? {
    if (request != null) return null
    val content = state.content
    return when {
        content is TrashContent.Error -> startList()
        content is TrashContent.Loaded && content.refreshFailure != null -> startList()
        content is TrashContent.Loaded && content.pageFailure != null -> startNextPage()
        else -> null
    }
}

private fun TrashMachine.startNextPage(): TrashMachine? {
    val content = state.content as? TrashContent.Loaded ?: return null
    val cursor = content.nextCursor
    return if (cursor == null || request != null || cursor in consumedCursors) null else copy(
        state = state.copy(
            content = content.copy(isLoadingMore = true, pageFailure = null),
            confirmation = null, confirmationId = null,
            actionConfirmation = null, actionConfirmationId = null,
        ),
        request = TrashRequest.ListPage(nextRequestId, cursor), nextRequestId = nextRequestId + 1L,
    )
}
