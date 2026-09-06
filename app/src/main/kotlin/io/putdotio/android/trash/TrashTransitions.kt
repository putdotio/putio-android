package io.putdotio.android.trash

internal fun TrashMachine.transition(event: TrashEvent): TrashMachine? {
    if (state.authenticationFailure != null) return null
    return when (event) {
        TrashEvent.Open -> if (opened) null else startList()
        TrashEvent.Refresh -> when (request) {
            null, is TrashRequest.ListPage -> startList()
            else -> null
        }
        TrashEvent.Retry -> retryList()
        TrashEvent.LoadNextPage -> startNextPage()
        is TrashEvent.SelectRestore -> selectRestore(event)
        TrashEvent.CancelRestore -> cancelRestore()
        is TrashEvent.ConfirmRestore -> confirmRestore(event)
        TrashEvent.CheckRestore -> startCheck()
        TrashEvent.DismissRestoreOutcome -> dismissRestoreOutcome()
    }
}

private fun TrashMachine.cancelRestore(): TrashMachine? =
    if (state.confirmation == null) null
    else copy(state = state.copy(confirmation = null, confirmationId = null))

private fun TrashMachine.dismissRestoreOutcome(): TrashMachine? =
    if (state.restoreOutcome == null || state.hasPendingRestore) null
    else copy(state = state.copy(restoreOutcome = null))

internal fun TrashMachine.startList(): TrashMachine {
    val content = (state.content as? TrashContent.Loaded)?.copy(
        isRefreshing = true, isLoadingMore = false, refreshFailure = null, pageFailure = null,
    ) ?: TrashContent.Loading
    return copy(
        state = state.copy(content = content, confirmation = null, confirmationId = null),
        request = TrashRequest.ListPage(nextRequestId), nextRequestId = nextRequestId + 1L, opened = true,
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
        ),
        request = TrashRequest.ListPage(nextRequestId, cursor), nextRequestId = nextRequestId + 1L,
    )
}

private fun TrashMachine.selectRestore(event: TrashEvent.SelectRestore): TrashMachine? {
    val item = (state.content as? TrashContent.Loaded)?.items?.firstOrNull { it.id == event.itemId }
    if (item == null || request != null || !state.canRestore(event.itemId)) return null
    return copy(
        state = state.copy(confirmation = item, confirmationId = nextRequestId, restoreOutcome = null),
        nextRequestId = nextRequestId + 1L,
    )
}

private fun TrashMachine.confirmRestore(event: TrashEvent.ConfirmRestore): TrashMachine? {
    val item = state.confirmation ?: return null
    val current = (state.content as? TrashContent.Loaded)?.items?.firstOrNull { it.id == item.id }
    val eligible = current == item && state.canRestore(item.id)
    return if (!eligible || request != null || event.confirmationId != state.confirmationId) null else copy(
        state = state.copy(
            confirmation = null, confirmationId = null,
            restoreOutcome = TrashRestoreOutcome(item, TrashRestoreSubmission.SUBMITTING),
        ),
        request = TrashRequest.Restore(nextRequestId, item), nextRequestId = nextRequestId + 1L,
    )
}

internal fun TrashMachine.startCheck(): TrashMachine? {
    val outcome = state.restoreOutcome ?: return null
    return if (request != null || !outcome.isPending || outcome.submission == TrashRestoreSubmission.SUBMITTING) {
        null
    } else copy(
        state = state.copy(restoreOutcome = outcome.copy(check = TrashRestoreCheck.CHECKING, checkFailure = null)),
        request = TrashRequest.Check(nextRequestId, outcome.item), nextRequestId = nextRequestId + 1L,
    )
}
