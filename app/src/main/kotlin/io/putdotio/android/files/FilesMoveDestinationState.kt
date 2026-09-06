package io.putdotio.android.files

@ConsistentCopyVisibility
data class FilesMoveDestinationFolder internal constructor(
    val folder: FilesFolder,
    val content: FilesContent,
    internal val consumedCursors: Set<FilesCursor> = emptySet(),
)

@ConsistentCopyVisibility
data class FilesMoveDestinationState internal constructor(
    val sourceItem: FilesItem,
    val sourceFolderId: FilesItemId,
    internal val stack: List<FilesMoveDestinationFolder>,
    internal val nextRequestValue: Long,
) {
    val current: FilesMoveDestinationFolder get() = stack.last()
    val path: List<FilesFolder> get() = stack.map { it.folder }
    val canNavigateBack: Boolean get() = stack.size > 1
    val canMoveHere: Boolean get() = sourceItem.id.value > 0L && current.folder.id.value >= 0L &&
        current.folder.id != sourceFolderId && current.folder.id != sourceItem.parentId &&
        current.folder.id != sourceItem.id &&
        (current.content is FilesContent.Ready || current.content is FilesContent.Empty)

    fun canOpenFolder(itemId: FilesItemId): Boolean = itemId.value > 0L && itemId != sourceItem.id &&
        stack.none { it.folder.id == itemId } && current.content.items().any { it.id == itemId && it.isFolder }
}

sealed interface FilesMoveDestinationEvent {
    data class OpenFolder(val itemId: FilesItemId) : FilesMoveDestinationEvent
    data object NavigateBack : FilesMoveDestinationEvent
    data object LoadNextPage : FilesMoveDestinationEvent
    data object Retry : FilesMoveDestinationEvent
}

internal data class FilesMoveDestinationRequest(
    val folderId: FilesItemId,
    val requestId: FilesRequestId,
    val cursor: FilesCursor? = null,
)

internal data class FilesMoveDestinationTransition(
    val state: FilesMoveDestinationState,
    val request: FilesMoveDestinationRequest? = null,
    val consumed: Boolean = true,
)

internal fun FilesMoveDestinationState.reduce(event: FilesMoveDestinationEvent): FilesMoveDestinationTransition =
    when (event) {
        is FilesMoveDestinationEvent.OpenFolder -> openDestination(event.itemId)
        FilesMoveDestinationEvent.NavigateBack -> if (canNavigateBack) {
            FilesMoveDestinationTransition(copy(stack = stack.dropLast(1)))
        } else {
            FilesMoveDestinationTransition(this, consumed = false)
        }
        FilesMoveDestinationEvent.LoadNextPage -> loadDestinationPage(retry = false)
        FilesMoveDestinationEvent.Retry -> if (current.content is FilesContent.Failed) {
            val requestId = FilesRequestId(nextRequestValue)
            FilesMoveDestinationTransition(
                copy(stack = stack.replaceLast(current.copy(content = FilesContent.Loading(requestId))),
                    nextRequestValue = nextRequestValue + 1),
                FilesMoveDestinationRequest(current.folder.id, requestId),
            )
        } else {
            loadDestinationPage(retry = true)
        }
    }

private fun FilesMoveDestinationState.openDestination(itemId: FilesItemId): FilesMoveDestinationTransition {
    if (!canOpenFolder(itemId)) return FilesMoveDestinationTransition(this, consumed = false)
    val item = current.content.items().first { it.id == itemId }
    val requestId = FilesRequestId(nextRequestValue)
    val parent = current.copy(content = current.content.withoutActivePagingRequest())
    val child = FilesMoveDestinationFolder(FilesFolder(item.id, item.name), FilesContent.Loading(requestId))
    return FilesMoveDestinationTransition(
        copy(stack = stack.replaceLast(parent) + child, nextRequestValue = nextRequestValue + 1),
        FilesMoveDestinationRequest(item.id, requestId),
    )
}

private fun FilesMoveDestinationState.loadDestinationPage(retry: Boolean): FilesMoveDestinationTransition {
    val paging = current.content.paging()
    val cursor = when {
        !retry && paging is FilesPaging.Available -> paging.cursor
        retry && paging is FilesPaging.Failed -> paging.cursor
        else -> return FilesMoveDestinationTransition(this, consumed = false)
    }
    val requestId = FilesRequestId(nextRequestValue)
    val content = current.content.withPaging(FilesPaging.Loading(cursor, requestId))
    return if (content == null) {
        FilesMoveDestinationTransition(this, consumed = false)
    } else {
        FilesMoveDestinationTransition(
            copy(stack = stack.replaceLast(current.copy(content = content)), nextRequestValue = nextRequestValue + 1),
            FilesMoveDestinationRequest(current.folder.id, requestId, cursor),
        )
    }
}

internal fun FilesMoveDestinationState.complete(
    request: FilesMoveDestinationRequest,
    result: FilesRepositoryResult<FilesPage>,
): FilesMoveDestinationState {
    if (current.folder.id != request.folderId || current.requestId() != request.requestId) return this
    val consumed = if (request.cursor == null) emptySet() else current.consumedCursors + request.cursor
    val content = when (result) {
        is FilesRepositoryResult.Success -> {
            val previous = if (request.cursor == null) emptyList() else current.content.items()
            val folders = result.value.items.filter { it.isFolder && it.id.value > 0L }
            contentFor(previous + folders, result.value.nextCursor.toPaging(consumed), current.content.viewport())
        }
        is FilesRepositoryResult.Failure -> if (request.cursor == null) {
            FilesContent.Failed(result.failure)
        } else {
            current.content.withPaging(FilesPaging.Failed(request.cursor, result.failure)) ?: current.content
        }
    }
    return copy(stack = stack.replaceLast(current.copy(
        content = content,
        consumedCursors = if (result is FilesRepositoryResult.Success) consumed else current.consumedCursors,
    )))
}

internal fun FilesMoveDestinationFolder.requestId(): FilesRequestId? =
    (content as? FilesContent.Loading)?.requestId ?: (content.paging() as? FilesPaging.Loading)?.requestId
