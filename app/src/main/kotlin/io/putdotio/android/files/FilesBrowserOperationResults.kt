package io.putdotio.android.files

internal fun FilesBrowserState.sortPersisted(event: FilesBrowserEvent.SortPersisted): FilesBrowserTransition =
    mutationSucceeded(event.requestId, FilesFolderOperationPhase.PERSISTING_SORT)

internal fun FilesBrowserState.renamed(event: FilesBrowserEvent.Renamed): FilesBrowserTransition =
    mutationSucceeded(event.requestId, FilesFolderOperationPhase.RENAMING)

private fun FilesBrowserState.mutationSucceeded(
    requestId: FilesRequestId,
    phase: FilesFolderOperationPhase,
): FilesBrowserTransition {
    val index = stack.indexOfFirst {
        (it.operation as? FilesFolderOperation.Loading)?.requestId == requestId
    }
    val folderState = stack.getOrNull(index)
    val loading = folderState?.operation as? FilesFolderOperation.Loading
    if (loading == null || loading.phase != phase) {
        return FilesBrowserTransition(this, consumed = false)
    }

    val reloadRequestId = FilesRequestId(nextRequestValue)
    val updated =
        folderState.copy(
            operation =
                FilesFolderOperation.Loading(
                    requestId = reloadRequestId,
                    intent = loading.intent,
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
            is FilesFolderOperationIntent.Rename -> content.viewport()
            is FilesFolderOperationIntent.Sort -> FilesViewportPosition()
        }
    // folder.sort changes only when the reordered rows replace the list, while the
    // viewport generation advances only for an explicit sort so refresh cannot reset it.
    val persistedSort = (loading.intent as? FilesFolderOperationIntent.Sort)?.sort
    return copy(
        folder = folder.copy(sort = page.sort ?: persistedSort ?: folder.sort),
        content = contentFor(page.items, page.nextCursor.toPaging(emptySet()), viewport),
        operation = FilesFolderOperation.Idle,
        viewportGeneration =
            when (loading.intent) {
                FilesFolderOperationIntent.Refresh -> viewportGeneration
                is FilesFolderOperationIntent.Rename -> viewportGeneration
                is FilesFolderOperationIntent.Sort -> viewportGeneration + 1
            },
        consumedCursors = emptySet(),
    )
}
