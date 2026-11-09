package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult

internal fun FilesBrowserState.reduceMove(event: FilesBrowserEvent.MoveEvent): FilesBrowserTransition = when (event) {
    is FilesBrowserEvent.Move -> move(event)
    is FilesBrowserEvent.MoveFinished -> moveFinished(event)
    is FilesBrowserEvent.MoveChecked -> moveChecked(event)
}

public val FilesBrowserState.canStartMove: Boolean
    get() = current.operation.canStartOperation &&
        stack.dropLast(1).all { it.operation == FilesFolderOperation.Idle }

private fun FilesBrowserState.move(event: FilesBrowserEvent.Move): FilesBrowserTransition {
    val items = current.content.listedItems(event.itemIds)
    if (event.folderId != current.folder.id || items == null || !canStartMove) {
        return FilesBrowserTransition(this, consumed = false)
    }
    val destination = event.destinationId
    val invalidDestination = destination.value < 0L || destination == current.folder.id ||
        items.any { destination == it.parentId || destination == it.id }
    return if (invalidDestination) {
        FilesBrowserTransition(this, consumed = false)
    } else {
        val intent = FilesFolderOperationIntent.Move(items.map { it.id }, destination)
        val started = startOperation(intent, FilesFolderOperationPhase.MOVING)
        val effect = started.effect
        if (effect == null) {
            started
        } else {
            val updated = started.state.current.copy(
                moveOutcome = FilesMoveOutcome(effect.requestId, intent, items.first().name), deleteOutcome = null,
            )
            // Either destination or source ancestors can have changed once the POST is submitted.
            val ancestors = started.state.stack.dropLast(1).map {
                it.copy(needsReload = true, content = it.content.withoutActivePagingRequest())
            }
            started.copy(state = started.state.copy(stack = ancestors + updated))
        }
    }
}

internal fun FilesBrowserState.moveFinished(event: FilesBrowserEvent.MoveFinished): FilesBrowserTransition {
    val index = requestIndex<FilesFolderOperationIntent.Move>(
        event.requestId,
        FilesFolderOperationPhase.MOVING,
    )
    val folder = stack.getOrNull(index)
    val outcome = folder?.moveOutcome
    if (folder == null || outcome == null) return FilesBrowserTransition(this, consumed = false)
    val recorded = outcome.recording(event.result)
    val failure = recorded.failure
    return if (failure is PutioFailure.AuthenticationRequired) {
        val updated = folder.copy(
            moveOutcome = recorded.copy(status = FilesMoveStatus.UNKNOWN),
            operation = FilesFolderOperation.Failed(failure, outcome.intent, FilesFolderOperationPhase.CHECKING_MOVE),
        )
        FilesBrowserTransition(copy(stack = stack.replaceAt(index, updated)))
    } else {
        val requestId = FilesRequestId(nextRequestValue)
        val updated = folder.copy(
            moveOutcome = recorded,
            operation = FilesFolderOperation.Loading(
                requestId, outcome.intent, FilesFolderOperationPhase.CHECKING_MOVE,
            ),
        )
        FilesBrowserTransition(
            copy(stack = stack.replaceAt(index, updated), nextRequestValue = nextRequestValue + 1),
            FilesBrowserEffect.CheckMove(outcome.intent.itemIds, requestId),
        )
    }
}

private fun FilesBrowserState.moveChecked(event: FilesBrowserEvent.MoveChecked): FilesBrowserTransition {
    val index = requestIndex<FilesFolderOperationIntent.Move>(
        event.requestId,
        FilesFolderOperationPhase.CHECKING_MOVE,
    )
    val folder = stack.getOrNull(index)
    val outcome = folder?.moveOutcome
    if (folder == null || outcome == null) return FilesBrowserTransition(this, consumed = false)
    return when (val check = outcome.checked(event.results)) {
        is FilesEditCheck.Unconfirmed -> loadFailed(FilesBrowserEvent.LoadFailed(event.requestId, check.failure))
        is FilesEditCheck.Settled -> {
            val requestId = FilesRequestId(nextRequestValue)
            val updated = folder.copy(
                moveOutcome = check.outcome,
                operation = FilesFolderOperation.Loading(
                    requestId,
                    outcome.intent,
                    FilesFolderOperationPhase.RELOADING,
                ),
                needsReload = false,
            )
            FilesBrowserTransition(
                copy(stack = stack.replaceAt(index, updated), nextRequestValue = nextRequestValue + 1),
                FilesBrowserEffect.LoadFolder(folder.folder.id, requestId),
            )
        }
    }
}

public val FilesFolderOperation.pendingMove: FilesFolderOperationIntent.Move?
    get() = pendingIntent()

/**
 * A page that lists a moved item again: the move left it here, whatever the check said. A refused
 * move keeps its status and counts the item too.
 */
internal fun FilesMoveOutcome.afterFolderPage(page: FilesPage): FilesMoveOutcome {
    val listed = page.items.mapNotNullTo(mutableSetOf()) { item -> item.id.takeIf { it in intent.itemIds } }
    return when {
        remainingIds.containsAll(listed) -> this
        status == FilesMoveStatus.MOVED || status == FilesMoveStatus.STILL_PRESENT ->
            copy(status = FilesMoveStatus.STILL_PRESENT, remainingIds = remainingIds + listed)
        status == FilesMoveStatus.REJECTED -> copy(remainingIds = remainingIds + listed)
        else -> this
    }
}
