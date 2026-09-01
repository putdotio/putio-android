package io.putdotio.android.files

internal fun FilesBrowserState.loadSucceeded(event: FilesBrowserEvent.LoadSucceeded): FilesBrowserTransition {
    val index = stack.indexOfFirst { it.hasRequest(event.requestId) }
    val updated = stack.getOrNull(index)?.loadSucceeded(event.requestId, event.page)
    return if (updated == null) {
        FilesBrowserTransition(this, consumed = false)
    } else {
        FilesBrowserTransition(copy(stack = stack.replaceAt(index, updated)))
    }
}

internal fun FilesBrowserState.loadFailed(event: FilesBrowserEvent.LoadFailed): FilesBrowserTransition {
    val index = stack.indexOfFirst { it.hasRequest(event.requestId) }
    val updated = stack.getOrNull(index)?.loadFailed(event.requestId, event.failure)
    return if (updated == null) {
        FilesBrowserTransition(this, consumed = false)
    } else {
        FilesBrowserTransition(copy(stack = stack.replaceAt(index, updated)))
    }
}

internal fun FilesBrowserState.hasRequest(requestId: FilesRequestId): Boolean =
    stack.any { it.hasRequest(requestId) }

private fun FilesFolderState.hasRequest(requestId: FilesRequestId): Boolean =
    operation.requestId() == requestId || when (val state = content) {
        is FilesContent.Loading -> state.requestId == requestId
        is FilesContent.Empty -> (state.paging as? FilesPaging.Loading)?.requestId == requestId
        is FilesContent.Ready -> (state.paging as? FilesPaging.Loading)?.requestId == requestId
        is FilesContent.Failed -> false
    }

private fun FilesFolderOperation.requestId(): FilesRequestId? =
    (this as? FilesFolderOperation.Loading)?.requestId

private fun FilesFolderState.loadSucceeded(
    requestId: FilesRequestId,
    page: FilesPage,
): FilesFolderState? =
    if (operation.requestId() == requestId) {
        replaceFirstPage(requestId, page)
    } else {
        when (val state = content) {
            is FilesContent.Loading ->
                if (state.requestId == requestId) {
                    copy(
                        folder = folder.copy(sort = page.sort ?: folder.sort),
                        content = contentFor(page.items, page.nextCursor.toPaging(consumedCursors)),
                        consumedCursors = emptySet(),
                    )
                } else {
                    null
                }

            is FilesContent.Empty,
            is FilesContent.Ready,
            -> appendPage(state, requestId, page)

            is FilesContent.Failed -> null
        }
    }

private fun FilesFolderState.appendPage(
    state: FilesContent,
    requestId: FilesRequestId,
    page: FilesPage,
): FilesFolderState? {
    val loading = state.paging() as? FilesPaging.Loading
    return if (loading == null || loading.requestId != requestId) {
        null
    } else {
        val updatedConsumedCursors = consumedCursors + loading.cursor
        val items = (state.items() + page.items).distinctBy(FilesItem::id)
        copy(
            content =
                contentFor(
                    items = items,
                    paging = page.nextCursor.toPaging(updatedConsumedCursors),
                    viewport = state.viewport(),
                ),
            consumedCursors = updatedConsumedCursors,
        )
    }
}

private fun FilesFolderState.loadFailed(
    requestId: FilesRequestId,
    failure: FilesFailure,
): FilesFolderState? =
    if (operation.requestId() == requestId) {
        val loading = operation as FilesFolderOperation.Loading
        copy(
            operation = FilesFolderOperation.Failed(failure, loading.intent, loading.phase),
        )
    } else {
        when (val state = content) {
            is FilesContent.Loading ->
                if (state.requestId == requestId) {
                    copy(content = FilesContent.Failed(failure))
                } else {
                    null
                }

            is FilesContent.Empty,
            is FilesContent.Ready,
            -> {
                val loading = state.paging() as? FilesPaging.Loading
                if (loading == null || loading.requestId != requestId) {
                    null
                } else {
                    copy(content = state.withPaging(FilesPaging.Failed(loading.cursor, failure)) ?: return null)
                }
            }

            is FilesContent.Failed -> null
        }
    }

internal fun contentFor(
    items: List<FilesItem>,
    paging: FilesPaging,
    viewport: FilesViewportPosition = FilesViewportPosition(),
): FilesContent =
    if (items.isEmpty()) {
        FilesContent.Empty(paging = paging, viewport = viewport)
    } else {
        FilesContent.Ready(items = items.distinctBy(FilesItem::id), paging = paging, viewport = viewport)
    }

internal fun FilesCursor?.toPaging(consumedCursors: Set<FilesCursor>): FilesPaging =
    if (this == null || this in consumedCursors) {
        FilesPaging.Complete
    } else {
        FilesPaging.Available(this)
    }
