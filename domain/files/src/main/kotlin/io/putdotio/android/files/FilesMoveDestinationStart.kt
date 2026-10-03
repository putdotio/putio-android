package io.putdotio.android.files

internal fun FilesMoveDestinationState.withoutRememberedTargetCheck() =
    if (opensRememberedTarget) copy(opensRememberedTarget = false) else this

/**
 * Web reopens at root when the remembered folder can't be read; a rejected session stays visible so
 * the app signs out. Null leaves the read to the ordinary listing.
 */
internal fun FilesMoveDestinationState.completeRememberedRead(
    request: FilesMoveDestinationRequest,
    result: FilesRepositoryResult<FilesPage>,
): FilesMoveDestinationTransition? = when (result) {
    is FilesRepositoryResult.Failure ->
        if (opensRememberedTarget && result.failure !is FilesFailure.AuthenticationRequired) restartAtRoot() else null
    is FilesRepositoryResult.Success ->
        if (current.remembered && request.cursor == null) reconcileRemembered(request, result) else null
}

/**
 * Each remembered folder is checked when first read: it takes its current name, one moved elsewhere
 * keeps only root above it, and one now directly inside the item being moved restarts at root.
 */
private fun FilesMoveDestinationState.reconcileRemembered(
    request: FilesMoveDestinationRequest,
    result: FilesRepositoryResult.Success<FilesPage>,
): FilesMoveDestinationTransition {
    val listed = result.value.parent?.takeIf { it.id == current.folder.id }
    if (sourceItem != null && listed?.parentId == sourceItem.id) return restartAtRoot()
    val read = current.copy(
        folder = current.folder.copy(name = listed?.name ?: current.folder.name),
        remembered = false,
    )
    val ancestors = stack.dropLast(1)
    val placed = ancestors.isEmpty() || listed?.parentId == null || listed.parentId == ancestors.last().folder.id
    return FilesMoveDestinationTransition(withoutRememberedTargetCheck().copy(
        stack = (if (placed) ancestors else listOf(unreadFolder(FilesFolder.Root))) + read,
    ).completeListing(request, result))
}

private fun FilesMoveDestinationState.restartAtRoot(): FilesMoveDestinationTransition {
    val requestId = FilesRequestId(nextRequestValue)
    return FilesMoveDestinationTransition(
        copy(
            stack = listOf(FilesMoveDestinationFolder(FilesFolder.Root, FilesContent.Loading(requestId))),
            nextRequestValue = nextRequestValue + 1,
            opensRememberedTarget = false,
        ),
        FilesMoveDestinationRequest(FilesFolder.Root.id, requestId),
    )
}

/** Root keeps its localized heading, so it is never checked against a listing. */
internal fun unreadFolder(folder: FilesFolder) =
    FilesMoveDestinationFolder(folder, FilesContent.Loading(UNREAD), remembered = folder.id != FilesFolder.Root.id)

/** Never issued: request IDs start at 1. */
private val UNREAD = FilesRequestId(0L)
