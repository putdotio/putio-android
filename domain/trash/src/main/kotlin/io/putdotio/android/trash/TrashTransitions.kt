package io.putdotio.android.trash

internal fun TrashMachine.transition(event: TrashEvent): TrashMachine? {
    if (state.authenticationFailure != null) return null
    return when (event) {
        is TrashEvent.ReadEvent -> read(event)
        is TrashEvent.RestoreEvent -> restore(event)
        is TrashEvent.ActionEvent -> action(event)
    }
}

private fun TrashMachine.restore(event: TrashEvent.RestoreEvent): TrashMachine? = when (event) {
    is TrashEvent.SelectRestore -> selectRestore(event)
    TrashEvent.CancelRestore -> cancelRestore()
    is TrashEvent.ConfirmRestore -> confirmRestore(event)
    TrashEvent.CheckRestore -> startCheck()
    TrashEvent.DismissRestoreOutcome -> dismissRestoreOutcome()
}

private fun TrashMachine.cancelRestore(): TrashMachine? =
    if (state.confirmation == null) null
    else copy(state = state.copy(confirmation = null, confirmationId = null))

private fun TrashMachine.dismissRestoreOutcome(): TrashMachine? =
    if (state.restoreOutcome == null || state.hasPendingRestore) null
    else copy(state = state.copy(restoreOutcome = null))

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
