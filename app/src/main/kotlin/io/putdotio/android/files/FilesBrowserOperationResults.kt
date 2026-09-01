package io.putdotio.android.files

internal fun FilesBrowserState.sortPersisted(event: FilesBrowserEvent.SortPersisted): FilesBrowserTransition {
    val index = stack.indexOfFirst {
        (it.operation as? FilesFolderOperation.Loading)?.requestId == event.requestId
    }
    val folderState = stack.getOrNull(index)
    val loading = folderState?.operation as? FilesFolderOperation.Loading
    val intent = loading?.intent as? FilesFolderOperationIntent.Sort
    if (loading == null || intent == null || loading.phase != FilesFolderOperationPhase.PERSISTING_SORT) {
        return FilesBrowserTransition(this, consumed = false)
    }

    val reloadRequestId = FilesRequestId(nextRequestValue)
    val updated =
        folderState.copy(
            operation =
                FilesFolderOperation.Loading(
                    requestId = reloadRequestId,
                    intent = intent,
                    phase = FilesFolderOperationPhase.RELOADING,
                ),
        )
    return FilesBrowserTransition(
        state = copy(stack = stack.replaceAt(index, updated), nextRequestValue = nextRequestValue + 1),
        effect = FilesBrowserEffect.LoadFolder(folderState.folder.id, reloadRequestId),
    )
}

internal fun FilesFolderState.replaceFirstPage(
    requestId: FilesRequestId,
    page: FilesPage,
): FilesFolderState? {
    val loading = operation as? FilesFolderOperation.Loading
    if (loading?.requestId != requestId || loading.phase != FilesFolderOperationPhase.RELOADING) {
        return null
    }
    val viewport =
        when (loading.intent) {
            FilesFolderOperationIntent.Refresh -> content.viewport()
            is FilesFolderOperationIntent.Sort -> FilesViewportPosition()
        }
    // folder.sort changes only when the reordered rows replace the list; the UI keys
    // its list state on it, so an earlier change would anchor scroll to the old order.
    val persistedSort = (loading.intent as? FilesFolderOperationIntent.Sort)?.sort
    return copy(
        folder = folder.copy(sort = page.sort ?: persistedSort ?: folder.sort),
        content = contentFor(page.items, page.nextCursor.toPaging(emptySet()), viewport),
        operation = FilesFolderOperation.Idle,
        consumedCursors = emptySet(),
    )
}
