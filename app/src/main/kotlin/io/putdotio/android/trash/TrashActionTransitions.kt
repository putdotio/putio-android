package io.putdotio.android.trash

import io.putdotio.android.files.FilesItemId

internal fun TrashMachine.action(event: TrashEvent.ActionEvent): TrashMachine? = when (event) {
    is TrashEvent.SelectDelete -> selectDelete(event)
    TrashEvent.SelectRestoreAll -> selectBulk(TrashAction.RestoreAll)
    TrashEvent.SelectEmpty -> selectBulk(TrashAction.Empty)
    TrashEvent.CancelAction -> cancelAction()
    is TrashEvent.ConfirmAction -> confirmAction(event)
    TrashEvent.CheckAction -> startActionCheck()
    TrashEvent.DismissActionOutcome -> dismissActionOutcome()
}

private fun TrashMachine.selectDelete(event: TrashEvent.SelectDelete): TrashMachine? {
    val item = loadedItem(event.itemId)
    if (item == null || request != null || !state.canDelete(event.itemId)) return null
    return openActionConfirmation(TrashAction.DeleteItem(item))
}

private fun TrashMachine.selectBulk(action: TrashAction): TrashMachine? =
    if (request != null || !state.canActOnAll) null else openActionConfirmation(action)

private fun TrashMachine.openActionConfirmation(action: TrashAction): TrashMachine = copy(
    state = state.copy(
        actionConfirmation = action, actionConfirmationId = nextRequestId, actionOutcome = null,
        confirmation = null, confirmationId = null,
    ),
    nextRequestId = nextRequestId + 1L,
)

private fun TrashMachine.loadedItem(itemId: FilesItemId): TrashItem? =
    (state.content as? TrashContent.Loaded)?.items?.firstOrNull { it.id == itemId }

private fun TrashMachine.cancelAction(): TrashMachine? =
    if (state.actionConfirmation == null) null
    else copy(state = state.copy(actionConfirmation = null, actionConfirmationId = null))

private fun TrashMachine.confirmAction(event: TrashEvent.ConfirmAction): TrashMachine? {
    val action = state.actionConfirmation
        ?.takeIf { request == null && event.confirmationId == state.actionConfirmationId && isEligible(it) }
        ?: return null
    // Restore all targets the listed snapshot: its cursor when the server issued one, else the loaded IDs.
    val content = state.content as TrashContent.Loaded
    val selection = (action as? TrashAction.RestoreAll)?.let {
        TrashBulkSelection(content.snapshotCursor, content.items.map(TrashItem::id))
    }
    val snapshot = selection?.let {
        TrashRestoreSnapshot(
            itemIds = it.itemIds.toSet(),
            coversUnloadedItems = it.cursor != null,
            // One unparseable row makes the bound unknown; a partial maximum would let snapshot rows pass as newer.
            newestDeletedAt = content.items.map { item -> item.deletedAt?.let(::parseTrashTimestamp) }
                .takeIf { stamps -> stamps.none { it == null } }?.filterNotNull()?.maxOrNull(),
        )
    }
    return copy(
        state = state.copy(
            actionConfirmation = null, actionConfirmationId = null,
            actionOutcome = TrashActionOutcome(action, TrashActionSubmission.SUBMITTING, restoreSnapshot = snapshot),
        ),
        request = TrashRequest.Act(nextRequestId, action, selection), nextRequestId = nextRequestId + 1L,
    )
}

private fun TrashMachine.isEligible(action: TrashAction): Boolean = when (action) {
    is TrashAction.DeleteItem -> loadedItem(action.item.id) == action.item && state.canDelete(action.item.id)
    TrashAction.RestoreAll, TrashAction.Empty -> state.canActOnAll
}

// Verification is a fresh initial read; it never resubmits the action.
internal fun TrashMachine.startActionCheck(): TrashMachine? {
    val outcome = state.actionOutcome?.takeIf {
        request == null && it.isPending && it.submission != TrashActionSubmission.SUBMITTING
    } ?: return null
    val checking = outcome.copy(check = TrashActionCheck.CHECKING, checkFailure = null)
    return copy(state = state.copy(actionOutcome = checking)).startList(verifiesAction = true)
}

private fun TrashMachine.dismissActionOutcome(): TrashMachine? =
    if (state.actionOutcome == null || state.hasPendingAction) null
    else copy(state = state.copy(actionOutcome = null))
