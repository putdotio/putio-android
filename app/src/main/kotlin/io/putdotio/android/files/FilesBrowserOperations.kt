package io.putdotio.android.files

internal fun FilesBrowserState.refresh(): FilesBrowserTransition =
    startOperation(
        intent = FilesFolderOperationIntent.Refresh,
        phase = FilesFolderOperationPhase.RELOADING,
    )

internal fun FilesBrowserState.selectSort(sort: FilesSort): FilesBrowserTransition =
    if (current.folder.sort == sort) {
        FilesBrowserTransition(this, consumed = false)
    } else {
        startOperation(
            intent = FilesFolderOperationIntent.Sort(sort),
            phase = FilesFolderOperationPhase.PERSISTING_SORT,
        )
    }

private fun FilesBrowserState.startOperation(
    intent: FilesFolderOperationIntent,
    phase: FilesFolderOperationPhase,
): FilesBrowserTransition {
    val hasVisibleContent = current.content is FilesContent.Empty || current.content is FilesContent.Ready
    if (current.operation is FilesFolderOperation.Loading || !hasVisibleContent) {
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
    return FilesBrowserTransition(
        state =
            copy(
                stack =
                    stack.replaceLast(
                        current.copy(
                            operation = FilesFolderOperation.Loading(requestId, operation.intent, operation.phase),
                        ),
                    ),
                nextRequestValue = nextRequestValue + 1,
            ),
        effect = effectFor(current.folder.id, requestId, operation.intent, operation.phase),
    )
}

private fun effectFor(
    folderId: FilesItemId,
    requestId: FilesRequestId,
    intent: FilesFolderOperationIntent,
    phase: FilesFolderOperationPhase,
): FilesBrowserEffect =
    when (phase) {
        FilesFolderOperationPhase.RELOADING -> FilesBrowserEffect.LoadFolder(folderId, requestId)
        FilesFolderOperationPhase.PERSISTING_SORT -> {
            val sort = (intent as FilesFolderOperationIntent.Sort).sort
            FilesBrowserEffect.PersistSort(folderId, sort, requestId)
        }
    }
