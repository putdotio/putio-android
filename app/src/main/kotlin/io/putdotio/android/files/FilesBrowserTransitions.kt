package io.putdotio.android.files

internal fun FilesBrowserState.openFolder(itemId: FilesItemId): FilesBrowserTransition {
    val item = current.content.items().firstOrNull { it.id == itemId && it.isFolder }
    return if (item == null || stack.any { it.folder.id == item.id }) {
        FilesBrowserTransition(this, consumed = false)
    } else {
        val requestId = FilesRequestId(nextRequestValue)
        val child =
            FilesFolderState(
                folder = FilesFolder(id = item.id, name = item.name),
                content = FilesContent.Loading(requestId),
            )
        FilesBrowserTransition(
            state = copy(stack = stack + child, nextRequestValue = nextRequestValue + 1),
            effect = FilesBrowserEffect.LoadFolder(item.id, requestId),
        )
    }
}

internal fun FilesBrowserState.navigateBack(): FilesBrowserTransition =
    if (canNavigateBack) {
        FilesBrowserTransition(copy(stack = stack.dropLast(1)))
    } else {
        FilesBrowserTransition(this, consumed = false)
    }

internal fun FilesBrowserState.loadNextPage(): FilesBrowserTransition {
    if (current.operation != FilesFolderOperation.Idle) {
        return FilesBrowserTransition(this, consumed = false)
    }
    val available = current.content.paging() as? FilesPaging.Available
    val requestId = FilesRequestId(nextRequestValue)
    val updatedContent = available?.let { current.content.withPaging(FilesPaging.Loading(it.cursor, requestId)) }
    return if (available == null || updatedContent == null) {
        FilesBrowserTransition(this)
    } else {
        FilesBrowserTransition(
            state =
                copy(
                    stack = stack.replaceLast(current.copy(content = updatedContent)),
                    nextRequestValue = nextRequestValue + 1,
                ),
            effect = FilesBrowserEffect.LoadNextPage(available.cursor, requestId),
        )
    }
}

internal fun FilesBrowserState.retry(): FilesBrowserTransition =
    when (val operation = current.operation) {
        is FilesFolderOperation.Failed -> startFailedOperation(operation)
        is FilesFolderOperation.Loading -> FilesBrowserTransition(this, consumed = false)
        FilesFolderOperation.Idle -> retryContent()
    }

private fun FilesBrowserState.retryContent(): FilesBrowserTransition {
    val requestId = FilesRequestId(nextRequestValue)
    return when (val content = current.content) {
        is FilesContent.Failed ->
            FilesBrowserTransition(
                state =
                    copy(
                        stack =
                            stack.replaceLast(
                                current.copy(
                                    content = FilesContent.Loading(requestId),
                                    consumedCursors = emptySet(),
                                ),
                            ),
                        nextRequestValue = nextRequestValue + 1,
                    ),
                effect = FilesBrowserEffect.LoadFolder(current.folder.id, requestId),
            )

        is FilesContent.Empty,
        is FilesContent.Ready,
        -> retryPage(content, requestId)

        is FilesContent.Loading -> FilesBrowserTransition(this)
    }
}

internal fun FilesBrowserState.rememberViewport(position: FilesViewportPosition): FilesBrowserTransition {
    val normalized =
        FilesViewportPosition(
            firstVisibleItemIndex = position.firstVisibleItemIndex.coerceAtLeast(0),
            firstVisibleItemScrollOffset = position.firstVisibleItemScrollOffset.coerceAtLeast(0),
        )
    val updated = current.content.withViewport(normalized)
    return if (updated == null) {
        FilesBrowserTransition(this)
    } else {
        FilesBrowserTransition(copy(stack = stack.replaceLast(current.copy(content = updated))))
    }
}

private fun FilesBrowserState.retryPage(
    content: FilesContent,
    requestId: FilesRequestId,
): FilesBrowserTransition {
    val failed = content.paging() as? FilesPaging.Failed
    val updated = failed?.let { content.withPaging(FilesPaging.Loading(it.cursor, requestId)) }
    return if (failed == null || updated == null) {
        FilesBrowserTransition(this)
    } else {
        FilesBrowserTransition(
            state =
                copy(
                    stack = stack.replaceLast(current.copy(content = updated)),
                    nextRequestValue = nextRequestValue + 1,
                ),
            effect = FilesBrowserEffect.LoadNextPage(failed.cursor, requestId),
        )
    }
}
