package io.putdotio.android.files

internal fun FilesMoveDestinationState.withoutRememberedTargetCheck() =
    if (opensRememberedTarget) copy(opensRememberedTarget = false) else this

/**
 * Web reopens at root when the remembered folder can't be read. A folder read since it was
 * remembered takes its current name, and one moved elsewhere keeps only root above it.
 */
internal fun FilesMoveDestinationState.completeRememberedTarget(
    request: FilesMoveDestinationRequest,
    result: FilesRepositoryResult<FilesPage>,
): FilesMoveDestinationTransition {
    val checked = copy(opensRememberedTarget = false)
    if (result !is FilesRepositoryResult.Success) {
        val requestId = FilesRequestId(nextRequestValue)
        return FilesMoveDestinationTransition(
            checked.copy(
                stack = listOf(FilesMoveDestinationFolder(FilesFolder.Root, FilesContent.Loading(requestId))),
                nextRequestValue = nextRequestValue + 1,
            ),
            FilesMoveDestinationRequest(FilesFolder.Root.id, requestId),
        )
    }
    val listed = result.value.parent?.takeIf { it.id == current.folder.id }
    val target = current.copy(folder = current.folder.copy(name = listed?.name ?: current.folder.name))
    val ancestors = stack.dropLast(1)
    val placed = listed?.parentId == null || listed.parentId == ancestors.last().folder.id
    return FilesMoveDestinationTransition(checked.copy(
        stack = (if (placed) ancestors else listOf(unreadFolder(FilesFolder.Root))) + target,
    ).completeListing(request, result))
}

internal fun unreadFolder(folder: FilesFolder) = FilesMoveDestinationFolder(folder, FilesContent.Loading(UNREAD))

/** Never issued: request IDs start at 1. */
private val UNREAD = FilesRequestId(0L)
