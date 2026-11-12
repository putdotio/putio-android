package io.putdotio.android.trash

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.parsePutioTimestamp

internal fun TrashMachine.action(event: TrashEvent.ActionEvent): TrashMachine? = when (event) {
    is TrashEvent.SelectDelete -> selectDelete(event)
    TrashEvent.RestoreAll -> if (mayActOnAll && state.actionConfirmation == null) submitAction(TrashAction.RestoreAll) else null
    TrashEvent.SelectEmpty -> if (mayActOnAll) openActionConfirmation(TrashAction.Empty) else null
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

private val TrashMachine.mayActOnAll: Boolean
    get() = request == null && state.canActOnAll

private fun TrashMachine.openActionConfirmation(action: TrashDestructiveAction): TrashMachine = copy(
    state = state.copy(
        actionConfirmation = action, actionConfirmationId = nextRequestId, actionOutcome = null,
    ),
    nextRequestId = nextRequestId + 1L,
)

internal fun TrashMachine.loadedItem(itemId: FilesItemId): TrashItem? =
    (state.content as? TrashContent.Loaded)?.items?.firstOrNull { it.id == itemId }

private fun TrashMachine.cancelAction(): TrashMachine? =
    if (state.actionConfirmation == null) null
    else copy(state = state.copy(actionConfirmation = null, actionConfirmationId = null))

private fun TrashMachine.confirmAction(event: TrashEvent.ConfirmAction): TrashMachine? {
    val action = state.actionConfirmation
        ?.takeIf { request == null && event.confirmationId == state.actionConfirmationId && isEligible(it) }
        ?: return null
    return submitAction(action)
}

private fun TrashMachine.submitAction(action: TrashAction): TrashMachine? {
    val content = state.content as? TrashContent.Loaded ?: return null
    val selection = (action as? TrashAction.RestoreAll)?.let {
        TrashBulkSelection(content.snapshotCursor, content.items.map(TrashItem::id))
    }
    val snapshot = selection?.let {
        TrashRestoreSnapshot(
            itemIds = it.itemIds.toSet(),
            coversUnloadedItems = it.cursor != null,
            // One unparseable row makes the bound unknown; a partial maximum would let snapshot rows pass as newer.
            newestDeletedAt = content.items.map { item -> item.deletedAt?.let(::parsePutioTimestamp) }
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

private fun TrashMachine.isEligible(action: TrashDestructiveAction): Boolean = when (action) {
    is TrashAction.DeleteItem -> loadedItem(action.item.id) == action.item && state.canDelete(action.item.id)
    TrashAction.Empty -> state.canActOnAll
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
