package io.putdotio.android.files

import io.putdotio.android.isNotFound

public data class FilesMissingFolder(
    val requestId: FilesRequestId,
    val folder: FilesFolder,
)

private fun FilesBrowserState.isMissingFolder(index: Int, event: FilesBrowserEvent.LoadFailed): Boolean {
    if (index != stack.lastIndex || !event.failure.isNotFound) return false
    val folder = stack[index]
    val firstPage = (folder.content as? FilesContent.Loading)?.requestId == event.requestId &&
        folder.revealSearch?.requestId != event.requestId
    val reload = (folder.operation as? FilesFolderOperation.Loading)
        ?.takeIf { it.requestId == event.requestId }
        ?.phase == FilesFolderOperationPhase.RELOADING
    return firstPage || reload
}

private fun FilesFolderState.without(itemId: FilesItemId): FilesFolderState {
    val listed = content as? FilesContent.Ready ?: return this
    val kept = listed.items.filterNot { it.id == itemId }
    val updated = if (kept.isEmpty()) FilesContent.Empty(listed.paging, listed.viewport) else listed.copy(items = kept)
    return copy(content = updated)
}

internal fun FilesBrowserState.leaveMissingFolder(
    index: Int,
    event: FilesBrowserEvent.LoadFailed,
): FilesBrowserTransition? {
    if (!isMissingFolder(index, event)) return null
    val missing = FilesMissingFolder(event.requestId, current.folder)
    val below = stack.take(index).map { it.without(missing.folder.id).copy(needsReload = true) }
    return copy(stack = below + current)
        .navigateBack(toRoot = current.openedFrom != null)
        .takeIf { it.consumed }
        ?.let { it.copy(state = it.state.copy(missingFolder = missing)) }
}

internal fun FilesBrowserState.missingFolderAnnounced(notice: FilesMissingFolder): FilesBrowserTransition =
    if (missingFolder == notice) {
        FilesBrowserTransition(copy(missingFolder = null))
    } else {
        FilesBrowserTransition(this, consumed = false)
    }
