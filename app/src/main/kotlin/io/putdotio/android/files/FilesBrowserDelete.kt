package io.putdotio.android.files

internal fun FilesBrowserState.reduceDelete(event: FilesBrowserEvent.DeleteEvent): FilesBrowserTransition =
    when (event) {
        is FilesBrowserEvent.Delete -> delete(event)
        is FilesBrowserEvent.DeleteFinished -> deleteFinished(event)
        is FilesBrowserEvent.DeleteChecked -> deleteChecked(event)
    }

private fun FilesBrowserState.delete(event: FilesBrowserEvent.Delete): FilesBrowserTransition {
    val item = current.content.items().firstOrNull { it.id == event.itemId && it.id.value > 0L }
    if (event.folderId != current.folder.id || item == null) {
        return FilesBrowserTransition(this, consumed = false)
    }
    val intent = FilesFolderOperationIntent.Delete(item.id, event.mode)
    val started = startOperation(intent, FilesFolderOperationPhase.DELETING)
    val effect = started.effect
    return if (effect == null) {
        started
    } else {
        val updated = started.state.current.copy(
            deleteOutcome = FilesDeleteOutcome(effect.requestId, intent, item.name),
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
    val failure = (event.result as? FilesRepositoryResult.Failure)?.failure
    val recorded = outcome.copy(
        response = (event.result as? FilesRepositoryResult.Success)?.value,
        failure = failure,
    )
    return if (failure is FilesFailure.AuthenticationRequired) {
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
            effect = FilesBrowserEffect.CheckDelete(outcome.intent.itemId, requestId),
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
    val failure = event.result.deleteReadFailure(outcome.intent.itemId)
    val unavailable = failure is FilesFailure.ApiRejected &&
        failure.statusCode == HTTP_NOT_FOUND && failure.httpStatusCode == HTTP_NOT_FOUND
    return if (failure != null && !unavailable) {
        loadFailed(FilesBrowserEvent.LoadFailed(event.requestId, failure))
    } else {
        val requestId = FilesRequestId(nextRequestValue)
        val updated = folder.copy(
            deleteOutcome = outcome.copy(status = outcome.checkedStatus(unavailable)),
            operation = FilesFolderOperation.Loading(requestId, outcome.intent, FilesFolderOperationPhase.RELOADING),
            needsReload = false,
        )
        FilesBrowserTransition(
            state = copy(stack = stack.replaceAt(index, updated), nextRequestValue = nextRequestValue + 1),
            effect = FilesBrowserEffect.LoadFolder(folder.folder.id, requestId),
        )
    }
}

private fun FilesRepositoryResult<FilesItem>.deleteReadFailure(expectedId: FilesItemId): FilesFailure? = when (this) {
    is FilesRepositoryResult.Failure -> failure
    is FilesRepositoryResult.Success -> if (value.id == expectedId) null else
        FilesFailure.Unexpected(IllegalStateException("File lookup returned a different ID"))
}

private fun FilesDeleteOutcome.checkedStatus(unavailable: Boolean): FilesDeleteStatus = when {
    response?.let { it.skipped > 0 || it.cursor != null } == true -> FilesDeleteStatus.SKIPPED
    unavailable -> FilesDeleteStatus.NO_LONGER_AVAILABLE
    else -> FilesDeleteStatus.STILL_PRESENT
}

internal val FilesFolderOperation.pendingDelete: FilesFolderOperationIntent.Delete?
    get() = pendingIntent()

internal fun FilesBrowserState.isDeleteTargetBlocked(itemId: FilesItemId): Boolean = stack.any {
    it.operation.pendingDelete?.itemId == itemId
}

private const val HTTP_NOT_FOUND = 404
