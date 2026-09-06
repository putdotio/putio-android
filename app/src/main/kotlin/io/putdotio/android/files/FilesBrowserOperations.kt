package io.putdotio.android.files

internal val FilesFolderOperation.canStartOperation: Boolean
    get() = when (this) {
        FilesFolderOperation.Idle -> true
        is FilesFolderOperation.Loading -> false
        is FilesFolderOperation.Failed -> intent !is FilesFolderOperationIntent.Move &&
            intent !is FilesFolderOperationIntent.Delete &&
            (intent !is FilesFolderOperationIntent.Rename || phase != FilesFolderOperationPhase.RELOADING)
    }

internal fun FilesBrowserState.abandonRename(event: FilesBrowserEvent.AbandonRename): FilesBrowserTransition {
    val failed = current.operation as? FilesFolderOperation.Failed
    return if (event.folderId == current.folder.id && failed?.intent == event.intent &&
        failed.phase == FilesFolderOperationPhase.RENAMING
    ) {
        FilesBrowserTransition(copy(stack = stack.replaceLast(current.copy(operation = FilesFolderOperation.Idle))))
    } else {
        FilesBrowserTransition(this, consumed = false)
    }
}

internal fun FilesBrowserState.rename(event: FilesBrowserEvent.Rename): FilesBrowserTransition {
    val item = current.content.items().firstOrNull { it.id == event.itemId && it.id.value > 0L }
    if (event.folderId != current.folder.id || item == null) {
        return FilesBrowserTransition(this, consumed = false)
    }
    return if (item.name == event.name) {
        FilesBrowserTransition(this, consumed = false)
    } else {
        startOperation(
            intent = FilesFolderOperationIntent.Rename(item.id, event.name),
            phase = FilesFolderOperationPhase.RENAMING,
        )
    }
}

internal fun FilesBrowserState.refresh(): FilesBrowserTransition =
    startOperation(
        intent = FilesFolderOperationIntent.Refresh,
        phase = FilesFolderOperationPhase.RELOADING,
    )

internal fun FilesBrowserState.selectSort(sort: FilesSort): FilesBrowserTransition =
    if (current.folder.sort == sort && current.operation == FilesFolderOperation.Idle) {
        FilesBrowserTransition(this, consumed = false)
    } else {
        startOperation(
            intent = FilesFolderOperationIntent.Sort(sort),
            phase = FilesFolderOperationPhase.PERSISTING_SORT,
        )
    }

internal fun FilesBrowserState.startOperation(
    intent: FilesFolderOperationIntent,
    phase: FilesFolderOperationPhase,
): FilesBrowserTransition {
    val hasVisibleContent = current.content is FilesContent.Empty || current.content is FilesContent.Ready
    if (!current.operation.canStartOperation || !hasVisibleContent) {
        return FilesBrowserTransition(this, consumed = false)
    }
    val requestId = FilesRequestId(nextRequestValue)
    val updated =
        current.copy(
            content = current.content.withoutActivePagingRequest(),
            operation = FilesFolderOperation.Loading(requestId, intent, phase),
        )
    return FilesBrowserTransition(
        state = copy(stack = stack.replaceLast(updated), nextRequestValue = nextRequestValue + 1),
        effect = effectFor(current.folder.id, requestId, intent, phase),
    )
}

internal fun FilesBrowserState.startFailedOperation(
    operation: FilesFolderOperation.Failed,
): FilesBrowserTransition {
    val requestId = FilesRequestId(nextRequestValue)
    // Mutations may have committed despite a failed response; recovery reads only.
    val phase = when {
        operation.intent is FilesFolderOperationIntent.Delete &&
            operation.phase == FilesFolderOperationPhase.DELETING ->
            FilesFolderOperationPhase.CHECKING_DELETE
        operation.intent is FilesFolderOperationIntent.Move && operation.phase == FilesFolderOperationPhase.MOVING ->
            FilesFolderOperationPhase.CHECKING_MOVE
        else -> operation.phase
    }
    return FilesBrowserTransition(
        state =
            copy(
                stack =
                    stack.replaceLast(
                        current.copy(
                            operation = FilesFolderOperation.Loading(requestId, operation.intent, phase),
                        ),
                    ),
                nextRequestValue = nextRequestValue + 1,
            ),
        effect = effectFor(current.folder.id, requestId, operation.intent, phase),
    )
}

private fun effectFor(
    folderId: FilesItemId,
    requestId: FilesRequestId,
    intent: FilesFolderOperationIntent,
    phase: FilesFolderOperationPhase,
): FilesBrowserEffect =
    if (phase == FilesFolderOperationPhase.RELOADING) {
        FilesBrowserEffect.LoadFolder(folderId, requestId)
    } else {
        when (intent) {
            FilesFolderOperationIntent.Refresh -> FilesBrowserEffect.LoadFolder(folderId, requestId)
            is FilesFolderOperationIntent.Sort -> FilesBrowserEffect.PersistSort(folderId, intent.sort, requestId)
            is FilesFolderOperationIntent.Rename -> FilesBrowserEffect.Rename(intent.itemId, intent.name, requestId)
            is FilesFolderOperationIntent.Move ->
                if (phase == FilesFolderOperationPhase.MOVING) {
                    FilesBrowserEffect.Move(intent.itemId, intent.destinationId, requestId)
                } else {
                    FilesBrowserEffect.CheckMove(intent.itemId, requestId)
                }
            is FilesFolderOperationIntent.Delete ->
                if (phase == FilesFolderOperationPhase.DELETING) {
                    FilesBrowserEffect.Delete(intent.itemId, intent.mode, requestId)
                } else {
                    FilesBrowserEffect.CheckDelete(intent.itemId, requestId)
                }
        }
    }
