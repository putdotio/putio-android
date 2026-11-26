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

internal fun FilesBrowserState.invalidateAllFolders(): FilesBrowserTransition =
    FilesBrowserTransition(copy(stack = stack.map { it.copy(needsReload = true) }))

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

internal fun FilesBrowserState.updatePlaybackPosition(itemId: FilesItemId, seconds: Double): FilesBrowserTransition {
    if (itemId.value <= 0L || !seconds.isFinite() || seconds < 0.0) {
        return FilesBrowserTransition(this, consumed = false)
    }
    var changed = false
    fun List<FilesItem>.withPosition(): List<FilesItem>? =
        if (none { it.id == itemId && it.isPlayable }) {
            null
        } else {
            changed = true
            map { item ->
                if (item.id == itemId && item.isPlayable) {
                    item.copy(playback = FilesPlaybackProgress(seconds, item.playback?.durationSeconds))
                } else {
                    item
                }
            }
        }
    val updated = stack.map { folder ->
        val content = folder.content as? FilesContent.Ready
        val search = folder.revealSearch
        content?.items?.withPosition()?.let { folder.copy(content = content.copy(items = it)) }
            ?: search?.items?.withPosition()?.let { folder.copy(revealSearch = search.copy(items = it)) }
            ?: folder
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
