package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult

internal fun FilesMoveDestinationState.withoutRememberedTargetCheck() =
    if (opensRememberedTarget) copy(opensRememberedTarget = false) else this

internal fun FilesMoveDestinationState.completeRememberedRead(
    request: FilesMoveDestinationRequest,
    result: PutioResult<FilesPage>,
): FilesMoveDestinationTransition? = when (result) {
    is PutioResult.Failure ->
        if (opensRememberedTarget && result.failure !is PutioFailure.AuthenticationRequired) restartAtRoot() else null
    is PutioResult.Success ->
        if (current.remembered && request.cursor == null) reconcileRemembered(request, result) else null
}

private fun FilesMoveDestinationState.reconcileRemembered(
    request: FilesMoveDestinationRequest,
    result: PutioResult.Success<FilesPage>,
): FilesMoveDestinationTransition {
    val listed = result.value.parent?.takeIf { it.id == current.folder.id }
    if (listed?.parentId?.let(::isSource) == true) return restartAtRoot()
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

internal fun unreadFolder(folder: FilesFolder) =
    FilesMoveDestinationFolder(folder, FilesContent.Loading(UNREAD), remembered = folder.id != FilesFolder.Root.id)

private val UNREAD = FilesRequestId(0L)
