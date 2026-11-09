package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult

internal fun FilesBrowserState.reduceDelete(event: FilesBrowserEvent.DeleteEvent): FilesBrowserTransition =
    when (event) {
        is FilesBrowserEvent.Delete -> delete(event)
        is FilesBrowserEvent.DeleteFinished -> deleteFinished(event)
        is FilesBrowserEvent.DeleteChecked -> deleteChecked(event)
        is FilesBrowserEvent.DeleteOutcomeAnnounced -> deleteOutcomeAnnounced(event.outcome)
        is FilesBrowserEvent.TrashUndone -> trashUndone(event.outcome)
    }

private fun FilesBrowserState.delete(event: FilesBrowserEvent.Delete): FilesBrowserTransition {
    val items = current.content.listedItems(event.itemIds)
    if (event.folderId != current.folder.id || items == null) {
        return FilesBrowserTransition(this, consumed = false)
    }
    val intent = FilesFolderOperationIntent.Delete(items.map { it.id }, event.mode)
    val started = startOperation(intent, FilesFolderOperationPhase.DELETING)
    val effect = started.effect
    return if (effect == null) {
        started
    } else {
        val updated = started.state.current.copy(
            deleteOutcome = FilesDeleteOutcome(effect.requestId, intent, items.first().name),
            moveOutcome = null,
        )
        started.copy(state = started.state.copy(stack = started.state.stack.replaceLast(updated)))
    }
}

internal fun FilesBrowserState.deleteFinished(event: FilesBrowserEvent.DeleteFinished): FilesBrowserTransition {
    val index = requestIndex<FilesFolderOperationIntent.Delete>(
        event.requestId,
        FilesFolderOperationPhase.DELETING,
    )
    val folder = stack.getOrNull(index)
    val outcome = folder?.deleteOutcome
    if (folder == null || outcome == null) return FilesBrowserTransition(this, consumed = false)
    val recorded = outcome.recording(event.result)
    val failure = recorded.failure
    return if (failure is PutioFailure.AuthenticationRequired) {
        val updated = folder.copy(
            deleteOutcome = recorded.copy(status = FilesDeleteStatus.UNKNOWN),
            operation = FilesFolderOperation.Failed(failure, outcome.intent, FilesFolderOperationPhase.CHECKING_DELETE),
        )
        FilesBrowserTransition(copy(stack = stack.replaceAt(index, updated)))
    } else {
        val requestId = FilesRequestId(nextRequestValue)
        val updated = folder.copy(
            deleteOutcome = recorded,
            operation = FilesFolderOperation.Loading(
                requestId, outcome.intent, FilesFolderOperationPhase.CHECKING_DELETE,
            ),
        )
        FilesBrowserTransition(
            state = copy(stack = stack.replaceAt(index, updated), nextRequestValue = nextRequestValue + 1),
            effect = FilesBrowserEffect.CheckDelete(outcome.intent.itemIds, requestId),
        )
    }
}

private fun FilesBrowserState.deleteChecked(event: FilesBrowserEvent.DeleteChecked): FilesBrowserTransition {
    val index = requestIndex<FilesFolderOperationIntent.Delete>(
        event.requestId,
        FilesFolderOperationPhase.CHECKING_DELETE,
    )
    val folder = stack.getOrNull(index)
    val outcome = folder?.deleteOutcome
    if (folder == null || outcome == null) return FilesBrowserTransition(this, consumed = false)
    return when (val check = outcome.checked(event.results)) {
        is FilesEditCheck.Unconfirmed -> loadFailed(FilesBrowserEvent.LoadFailed(event.requestId, check.failure))
        is FilesEditCheck.Settled -> {
            val requestId = FilesRequestId(nextRequestValue)
            val updated = folder.copy(
                deleteOutcome = check.outcome,
                operation = FilesFolderOperation.Loading(
                    requestId,
                    outcome.intent,
                    FilesFolderOperationPhase.RELOADING,
                ),
                needsReload = false,
            )
            FilesBrowserTransition(
                state = copy(stack = stack.replaceAt(index, updated), nextRequestValue = nextRequestValue + 1),
                effect = FilesBrowserEffect.LoadFolder(folder.folder.id, requestId),
            )
        }
    }
}

private fun FilesBrowserState.deleteOutcomeAnnounced(outcome: FilesDeleteOutcome): FilesBrowserTransition {
    val index = stack.indexOfFirst {
        it.deleteOutcome == outcome && !outcome.announced && it.operation.pendingDelete == null
    }
    if (index < 0) return FilesBrowserTransition(this, consumed = false)
    val announced = stack[index].copy(deleteOutcome = outcome.copy(announced = true))
    return FilesBrowserTransition(copy(stack = stack.replaceAt(index, announced)))
}

private fun FilesBrowserState.trashUndone(outcome: FilesDeleteOutcome): FilesBrowserTransition {
    val index = stack.indexOfFirst {
        it.deleteOutcome?.requestId == outcome.requestId && it.operation.pendingDelete == null
    }
    if (index < 0 || outcome.intent.mode != FilesDeleteMode.TRASH) return FilesBrowserTransition(this, consumed = false)
    val undone = stack[index].copy(deleteOutcome = null, needsReload = true)
    return FilesBrowserTransition(copy(stack = stack.replaceAt(index, undone)))
}

public val FilesFolderOperation.pendingDelete: FilesFolderOperationIntent.Delete?
    get() = pendingIntent()

internal fun FilesBrowserState.isDeleteTargetBlocked(itemId: FilesItemId): Boolean = stack.any {
    it.operation.pendingDelete?.itemIds?.contains(itemId) == true
}

