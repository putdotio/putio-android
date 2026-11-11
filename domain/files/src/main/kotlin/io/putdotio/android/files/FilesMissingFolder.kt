package io.putdotio.android.files

import io.putdotio.android.isNotFound

/**
 * A folder put.io answered 404 for. The browser left it for its parent, or for the root when an
 * outside open pushed it, and reloaded that folder; the screen says "File not found" once.
 */
public data class FilesMissingFolder(
    val requestId: FilesRequestId,
    val folder: FilesFolder,
)

internal fun FilesBrowserState.isMissingFolder(index: Int, event: FilesBrowserEvent.LoadFailed): Boolean {
    if (index != stack.lastIndex || !event.failure.isNotFound) return false
    val folder = stack[index]
    val firstPage = (folder.content as? FilesContent.Loading)?.requestId == event.requestId &&
        folder.revealSearch?.requestId != event.requestId
    val reload = (folder.operation as? FilesFolderOperation.Loading)
        ?.takeIf { it.requestId == event.requestId }
        ?.phase == FilesFolderOperationPhase.RELOADING
    return firstPage || reload
}

internal fun FilesBrowserState.leaveMissingFolder(requestId: FilesRequestId): FilesBrowserTransition? {
    val missing = FilesMissingFolder(requestId, current.folder)
    val back = navigateBack(toRoot = current.openedFrom != null).takeIf { it.consumed } ?: return null
    val landed = back.state.copy(missingFolder = missing)
    return if (back.effect != null) {
        back.copy(state = landed)
    } else {
        landed.copy(stack = landed.stack.replaceLast(landed.current.copy(needsReload = true))).reloadStaleFolder()
    }
}

internal fun FilesBrowserState.missingFolderAnnounced(notice: FilesMissingFolder): FilesBrowserTransition =
    if (missingFolder == notice) {
        FilesBrowserTransition(copy(missingFolder = null))
    } else {
        FilesBrowserTransition(this, consumed = false)
    }
