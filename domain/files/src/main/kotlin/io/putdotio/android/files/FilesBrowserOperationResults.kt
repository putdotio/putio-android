package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult

internal fun FilesBrowserState.mutationSucceeded(
    requestId: FilesRequestId,
): FilesBrowserTransition {
    val index = stack.indexOfFirst {
        (it.operation as? FilesFolderOperation.Loading)?.requestId == requestId
    }
    val folderState = stack.getOrNull(index)
    val loading = folderState?.operation as? FilesFolderOperation.Loading
    val requiresReadback = loading?.intent is FilesFolderOperationIntent.Delete ||
        loading?.intent is FilesFolderOperationIntent.Move
    if (loading == null || loading.phase == FilesFolderOperationPhase.RELOADING || requiresReadback) {
        return FilesBrowserTransition(this, consumed = false)
    }

    val reloadRequestId = FilesRequestId(nextRequestValue)
    val updated =
        folderState.copy(
            needsReload = false,
            renameCompletion = (loading.intent as? FilesFolderOperationIntent.Rename)
                ?.let { FilesRenameCompletion(requestId, it) }
                ?: folderState.renameCompletion,
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
    val persistedSort = when (val intent = loading.intent) {
        is FilesFolderOperationIntent.Sort -> intent.sort
        FilesFolderOperationIntent.Refresh,
        is FilesFolderOperationIntent.Rename,
        is FilesFolderOperationIntent.Delete,
        is FilesFolderOperationIntent.Move,
        -> null
    }
    // Advance the viewport generation only when an explicit sort replaces the rows.
    val viewport = if (persistedSort == null) content.viewport() else FilesViewportPosition()
    return copy(
        folder = folder.copy(sort = page.sort ?: persistedSort ?: folder.sort),
        summary = page.summary() ?: summary,
        content = contentFor(page.items, page.nextCursor.toPaging(emptySet()), viewport),
        operation = FilesFolderOperation.Idle,
        deleteOutcome = deleteOutcome?.afterFolderPage(page),
        moveOutcome = moveOutcome?.afterFolderPage(page),
        viewportGeneration = if (persistedSort == null) viewportGeneration else viewportGeneration + 1,
        consumedCursors = emptySet(),
    )
}

internal fun FilesDeleteOutcome.afterFolderPage(page: FilesPage): FilesDeleteOutcome {
    val listed = page.items.count { it.id in intent.itemIds }
    return if (status == FilesDeleteStatus.NO_LONGER_AVAILABLE && listed > 0) {
        copy(status = FilesDeleteStatus.STILL_PRESENT, remainingCount = listed)
    } else {
        this
    }
}

/** One read per item, in order; a check that read some other number of items read nothing. */
internal fun List<FilesItemId>.zipReads(
    results: List<PutioResult<FilesItem>>,
): List<Pair<FilesItemId, PutioResult<FilesItem>>> =
    if (results.size == size) {
        zip(results)
    } else {
        map { it to PutioResult.Failure(PutioFailure.Unexpected(IllegalStateException("Item reads did not match"))) }
    }
