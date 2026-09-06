package io.putdotio.android.files

internal fun FilesBrowserState.invalidateRestoredItem(item: FilesItem): FilesBrowserTransition {
    if (item.id.value <= 0L || item.parentId == null || item.parentId.value < 0L) {
        return FilesBrowserTransition(this, consumed = false)
    }
    val invalidated = stack.map { folder ->
        if (folder.folder.id == item.parentId || folder.folder.id == FilesFolder.Root.id) {
            folder.copy(needsReload = true)
        } else {
            folder
        }
    }
    return FilesBrowserTransition(copy(stack = invalidated))
}

// A bulk restore can return items to any folder; mark every cached level stale without navigating.
internal fun FilesBrowserState.invalidateAllFolders(): FilesBrowserTransition =
    FilesBrowserTransition(copy(stack = stack.map { it.copy(needsReload = true) }))

internal fun FilesBrowserState.reloadIfStale(): FilesBrowserTransition =
    if (current.needsReload && current.operation == FilesFolderOperation.Idle &&
        current.content !is FilesContent.Loading
    ) {
        reloadStaleFolder()
    } else {
        FilesBrowserTransition(this, consumed = false)
    }
