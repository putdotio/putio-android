package io.putdotio.android.files

@JvmInline
value class FilesRequestId(
    val value: Long,
)

data class FilesViewportPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)

sealed interface FilesPaging {
    data object Complete : FilesPaging

    data class Available(
        val cursor: FilesCursor,
    ) : FilesPaging

    data class Loading(
        val cursor: FilesCursor,
        val requestId: FilesRequestId,
    ) : FilesPaging

    data class Failed(
        val cursor: FilesCursor,
        val failure: FilesFailure,
    ) : FilesPaging
}

sealed interface FilesContent {
    data class Loading(
        val requestId: FilesRequestId,
    ) : FilesContent

    data class Empty(
        val paging: FilesPaging,
        val viewport: FilesViewportPosition = FilesViewportPosition(),
    ) : FilesContent

    data class Ready(
        val items: List<FilesItem>,
        val paging: FilesPaging,
        val viewport: FilesViewportPosition = FilesViewportPosition(),
    ) : FilesContent {
        init {
            require(items.isNotEmpty()) { "Ready Files content must contain at least one item" }
        }
    }

    data class Failed(
        val failure: FilesFailure,
    ) : FilesContent
}

data class FilesFolderState(
    val folder: FilesFolder,
    val content: FilesContent,
    internal val consumedCursors: Set<FilesCursor> = emptySet(),
)

@ConsistentCopyVisibility
data class FilesBrowserState internal constructor(
    val stack: List<FilesFolderState>,
    internal val nextRequestValue: Long,
) {
    init {
        require(stack.isNotEmpty()) { "The Files browser must contain a root folder" }
    }

    val current: FilesFolderState
        get() = stack.last()

    val path: List<FilesFolder>
        get() = stack.map(FilesFolderState::folder)

    val canNavigateBack: Boolean
        get() = stack.size > 1
}

sealed interface FilesBrowserEvent {
    data class OpenFolder(
        val itemId: FilesItemId,
    ) : FilesBrowserEvent

    data object NavigateBack : FilesBrowserEvent

    data object LoadNextPage : FilesBrowserEvent

    data object Retry : FilesBrowserEvent

    data class ViewportChanged(
        val position: FilesViewportPosition,
    ) : FilesBrowserEvent

    data class LoadSucceeded(
        val requestId: FilesRequestId,
        val page: FilesPage,
    ) : FilesBrowserEvent

    data class LoadFailed(
        val requestId: FilesRequestId,
        val failure: FilesFailure,
    ) : FilesBrowserEvent
}

sealed interface FilesBrowserEffect {
    val requestId: FilesRequestId

    data class LoadFolder(
        val folderId: FilesItemId,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    data class LoadNextPage(
        val cursor: FilesCursor,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect
}

data class FilesBrowserTransition(
    val state: FilesBrowserState,
    val effect: FilesBrowserEffect? = null,
    val consumed: Boolean = true,
)

object FilesBrowserReducer {
    fun start(): FilesBrowserTransition {
        val requestId = FilesRequestId(INITIAL_REQUEST_VALUE)
        val state =
            FilesBrowserState(
                stack =
                    listOf(
                        FilesFolderState(
                            folder = FilesFolder.Root,
                            content = FilesContent.Loading(requestId),
                        ),
                    ),
                nextRequestValue = INITIAL_REQUEST_VALUE + 1,
            )

        return FilesBrowserTransition(
            state = state,
            effect = FilesBrowserEffect.LoadFolder(FilesFolder.Root.id, requestId),
        )
    }

    fun reduce(
        state: FilesBrowserState,
        event: FilesBrowserEvent,
    ): FilesBrowserTransition =
        when (event) {
            is FilesBrowserEvent.OpenFolder -> state.openFolder(event.itemId)
            FilesBrowserEvent.NavigateBack -> state.navigateBack()
            FilesBrowserEvent.LoadNextPage -> state.loadNextPage()
            FilesBrowserEvent.Retry -> state.retry()
            is FilesBrowserEvent.ViewportChanged -> state.rememberViewport(event.position)
            is FilesBrowserEvent.LoadSucceeded -> state.loadSucceeded(event)
            is FilesBrowserEvent.LoadFailed -> state.loadFailed(event)
        }
}

suspend fun FilesRepository.execute(effect: FilesBrowserEffect): FilesBrowserEvent {
    val result =
        when (effect) {
            is FilesBrowserEffect.LoadFolder -> loadFolder(effect.folderId)
            is FilesBrowserEffect.LoadNextPage -> loadNextPage(effect.cursor)
        }

    return when (result) {
        is FilesRepositoryResult.Success -> FilesBrowserEvent.LoadSucceeded(effect.requestId, result.value)
        is FilesRepositoryResult.Failure -> FilesBrowserEvent.LoadFailed(effect.requestId, result.failure)
    }
}

private const val INITIAL_REQUEST_VALUE = 1L
