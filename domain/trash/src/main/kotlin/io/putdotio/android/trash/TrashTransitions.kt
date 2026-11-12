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
    is TrashEvent.Restore -> submitRestore(event)
    TrashEvent.CheckRestore -> startCheck()
    TrashEvent.DismissRestoreOutcome -> dismissRestoreOutcome()
}

private fun TrashMachine.dismissRestoreOutcome(): TrashMachine? =
    if (state.restoreOutcome == null || state.hasPendingRestore) null
    else copy(state = state.copy(restoreOutcome = null))

private fun TrashMachine.submitRestore(event: TrashEvent.Restore): TrashMachine? {
    val item = loadedItem(event.itemId)
    if (item == null || request != null || state.actionConfirmation != null || !state.canRestore(event.itemId)) {
        return null
    }
    return copy(
        state = state.copy(
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
