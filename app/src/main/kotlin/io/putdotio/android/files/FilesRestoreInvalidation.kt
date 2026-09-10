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

// Duration comes only from a cached progress; a row without one shows the unlabelled watched
// indicator until the next read supplies it.
internal fun FilesBrowserState.updatePlaybackPosition(itemId: FilesItemId, seconds: Double): FilesBrowserTransition {
    if (itemId.value <= 0L || !seconds.isFinite() || seconds < 0.0) {
        return FilesBrowserTransition(this, consumed = false)
    }
    var changed = false
    val updated = stack.map { folder ->
        val content = folder.content as? FilesContent.Ready ?: return@map folder
        if (content.items.none { it.id == itemId && it.isPlayable }) return@map folder
        changed = true
        val items = content.items.map { item ->
            if (item.id == itemId && item.isPlayable) {
                item.copy(playback = FilesPlaybackProgress(seconds, item.playback?.durationSeconds))
            } else {
                item
            }
        }
        folder.copy(content = content.copy(items = items))
    }
    return if (changed) {
        FilesBrowserTransition(copy(stack = updated))
    } else {
        FilesBrowserTransition(this, consumed = false)
    }
}

internal fun FilesBrowserState.reloadIfStale(): FilesBrowserTransition =
    if (current.needsReload && current.operation == FilesFolderOperation.Idle &&
        current.content !is FilesContent.Loading
    ) {
        reloadStaleFolder()
    } else {
        FilesBrowserTransition(this, consumed = false)
    }
