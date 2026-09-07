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

// The server reports every folder's effective `sort_by`, so cached state cannot tell an
// inherited order from an explicit one. Treat all levels as reordered: mark them stale and
// drop their scroll positions, since an index into the old order is meaningless in the new one.
internal fun FilesBrowserState.invalidateSortOrder(): FilesBrowserTransition =
    FilesBrowserTransition(
        copy(
            stack = stack.map { folder ->
                folder.copy(
                    needsReload = true,
                    content = folder.content.withViewport(FilesViewportPosition()) ?: folder.content,
                    viewportGeneration = folder.viewportGeneration + 1,
                )
            },
        ),
    )

internal fun FilesBrowserState.reloadIfStale(): FilesBrowserTransition =
    if (current.needsReload && current.operation == FilesFolderOperation.Idle &&
        current.content !is FilesContent.Loading
    ) {
        reloadStaleFolder()
    } else {
        FilesBrowserTransition(this, consumed = false)
    }
