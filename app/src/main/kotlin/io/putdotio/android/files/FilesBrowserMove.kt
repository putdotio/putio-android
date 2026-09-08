package io.putdotio.android.files

internal fun FilesBrowserState.reduceMove(event: FilesBrowserEvent.MoveEvent): FilesBrowserTransition = when (event) {
    is FilesBrowserEvent.Move -> move(event)
    is FilesBrowserEvent.MoveFinished -> moveFinished(event)
    is FilesBrowserEvent.MoveChecked -> moveChecked(event)
}

internal val FilesBrowserState.canStartMove: Boolean
    get() = current.operation.canStartOperation &&
        stack.dropLast(1).all { it.operation == FilesFolderOperation.Idle }

private fun FilesBrowserState.move(event: FilesBrowserEvent.Move): FilesBrowserTransition {
    val item = current.content.items().firstOrNull { it.id == event.itemId && it.id.value > 0L }
    if (event.folderId != current.folder.id || item == null || !canStartMove
    ) return FilesBrowserTransition(this, consumed = false)
    val sameParent = event.destinationId == current.folder.id || event.destinationId == item.parentId
    val invalidDestination = event.destinationId.value < 0L || sameParent || event.destinationId == item.id
    return if (invalidDestination) {
        FilesBrowserTransition(this, consumed = false)
    } else {
        val intent = FilesFolderOperationIntent.Move(item.id, event.destinationId)
        val started = startOperation(intent, FilesFolderOperationPhase.MOVING)
        val effect = started.effect
        if (effect == null) {
            started
        } else {
            val updated = started.state.current.copy(
                moveOutcome = FilesMoveOutcome(effect.requestId, intent, item.name), deleteOutcome = null,
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
    val index = requestIndex<FilesFolderOperationIntent.Move>(event.requestId, FilesFolderOperationPhase.MOVING)
    val folder = stack.getOrNull(index)
    val outcome = folder?.moveOutcome
    if (folder == null || outcome == null) return FilesBrowserTransition(this, consumed = false)
    val failure = (event.result as? FilesRepositoryResult.Failure)?.failure
    val recorded = outcome.copy(
        errors = (event.result as? FilesRepositoryResult.Success)?.value,
        failure = failure,
    )
    return if (failure is FilesFailure.AuthenticationRequired) {
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
            FilesBrowserEffect.CheckMove(outcome.intent.itemId, requestId),
        )
    }
}

private fun FilesBrowserState.moveChecked(event: FilesBrowserEvent.MoveChecked): FilesBrowserTransition {
    val index = requestIndex<FilesFolderOperationIntent.Move>(event.requestId, FilesFolderOperationPhase.CHECKING_MOVE)
    val folder = stack.getOrNull(index)
    val outcome = folder?.moveOutcome
    if (folder == null || outcome == null) return FilesBrowserTransition(this, consumed = false)
    val failure = event.result.moveReadFailure(outcome.intent.itemId)
    return if (failure != null) {
        loadFailed(FilesBrowserEvent.LoadFailed(event.requestId, failure))
    } else {
        val item = (event.result as? FilesRepositoryResult.Success)?.value
        val status = when {
            !outcome.errors.isNullOrEmpty() -> FilesMoveStatus.REJECTED
            item?.parentId == outcome.intent.destinationId -> FilesMoveStatus.MOVED
            else -> FilesMoveStatus.STILL_PRESENT
        }
        val requestId = FilesRequestId(nextRequestValue)
        val updated = folder.copy(
            moveOutcome = outcome.copy(status = status),
            operation = FilesFolderOperation.Loading(requestId, outcome.intent, FilesFolderOperationPhase.RELOADING),
            needsReload = false,
        )
        FilesBrowserTransition(
            copy(stack = stack.replaceAt(index, updated), nextRequestValue = nextRequestValue + 1),
            FilesBrowserEffect.LoadFolder(folder.folder.id, requestId),
        )
    }
}

private fun FilesRepositoryResult<FilesItem>.moveReadFailure(expectedId: FilesItemId): FilesFailure? = when (this) {
    is FilesRepositoryResult.Failure -> failure
    is FilesRepositoryResult.Success -> if (value.id == expectedId && (value.parentId?.value ?: -1L) >= 0L) {
        null
    } else {
        FilesFailure.Unexpected(IllegalStateException("Move lookup returned a different ID or no valid parent"))
    }
}

internal val FilesFolderOperation.pendingMove: FilesFolderOperationIntent.Move?
    get() = pendingIntent()

internal fun FilesMoveOutcome.afterFolderPage(page: FilesPage): FilesMoveOutcome =
    if (status == FilesMoveStatus.MOVED && page.items.any { it.id == intent.itemId }) {
        copy(status = FilesMoveStatus.STILL_PRESENT)
    } else {
        this
    }
