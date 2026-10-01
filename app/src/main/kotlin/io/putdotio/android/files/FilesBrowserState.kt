package io.putdotio.android.files

import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FileMoveError

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

sealed interface FilesFolderOperationIntent {
    data object Refresh : FilesFolderOperationIntent

    data class Sort(
        val sort: FilesSort,
    ) : FilesFolderOperationIntent

    data class Rename(
        val itemId: FilesItemId,
        val name: String,
    ) : FilesFolderOperationIntent

    data class Move(val itemId: FilesItemId, val destinationId: FilesItemId) : FilesFolderOperationIntent

    data class Delete(
        val itemId: FilesItemId,
        val mode: FilesDeleteMode,
    ) : FilesFolderOperationIntent
}

enum class FilesFolderOperationPhase {
    PERSISTING_SORT,
    RENAMING,
    DELETING,
    CHECKING_DELETE,
    MOVING,
    CHECKING_MOVE,
    RELOADING,
}

sealed interface FilesFolderOperation {
    data object Idle : FilesFolderOperation

    data class Loading(
        val requestId: FilesRequestId,
        val intent: FilesFolderOperationIntent,
        val phase: FilesFolderOperationPhase,
    ) : FilesFolderOperation

    data class Failed(
        val failure: FilesFailure,
        val intent: FilesFolderOperationIntent,
        val phase: FilesFolderOperationPhase,
    ) : FilesFolderOperation
}

data class FilesRenameCompletion(
    val requestId: FilesRequestId,
    val intent: FilesFolderOperationIntent.Rename,
)

data class FilesFolderState(
    val folder: FilesFolder,
    val content: FilesContent,
    val operation: FilesFolderOperation = FilesFolderOperation.Idle,
    val viewportGeneration: Long = 0L,
    val renameCompletion: FilesRenameCompletion? = null,
    val deleteOutcome: FilesDeleteOutcome? = null,
    val moveOutcome: FilesMoveOutcome? = null,
    /** Set on the folder an outside open pushed; Back from it returns to that origin. */
    val openedFrom: FilesOpenOrigin? = null,
    /**
     * The file this folder opens at: the one an outside open came for, or one the folder was asked
     * to show. Pages past the first are read for it, up to [MAX_REVEAL_PAGES].
     */
    val revealItemId: FilesItemId? = null,
    internal val revealSearch: FilesRevealSearch? = null,
    // Cleared when a full read starts so later invalidations survive that read's result.
    internal val needsReload: Boolean = false,
    internal val consumedCursors: Set<FilesCursor> = emptySet(),
)

@ConsistentCopyVisibility
data class FilesBrowserState internal constructor(
    val stack: List<FilesFolderState>,
    internal val nextRequestValue: Long,
    val copyOutcome: FilesCopyOutcome? = null,
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

    /** A folder opens itself and any other item its parent, on top of the current location. */
    data class OpenExternalItem(
        val item: FilesItem,
        val origin: FilesOpenOrigin,
    ) : FilesBrowserEvent

    data object NavigateBack : FilesBrowserEvent

    data object LoadNextPage : FilesBrowserEvent

    /** Shows [itemId] when [folderId] is the current folder, reading later pages for it when needed. */
    data class RevealItem(
        val folderId: FilesItemId,
        val itemId: FilesItemId,
    ) : FilesBrowserEvent

    data object Refresh : FilesBrowserEvent

    /** Staleness signals from other surfaces; they never navigate or mutate on their own. */
    sealed interface InvalidationEvent : FilesBrowserEvent

    data class InvalidateRestoredItem(val item: FilesItem) : InvalidationEvent

    data object InvalidateAllFolders : InvalidationEvent

    /** The account default order changed; every cached listing may now be in the wrong order. */
    data object InvalidateSortOrder : InvalidationEvent

    data object ReloadIfStale : InvalidationEvent

    /** A saved position for [itemId]; cached rows update in place so leaving playback shows the new progress. */
    data class PlaybackPositionReported(val itemId: FilesItemId, val seconds: Double) : InvalidationEvent

    data class SelectSort(
        val sort: FilesSort,
    ) : FilesBrowserEvent

    data class Rename(
        val folderId: FilesItemId,
        val itemId: FilesItemId,
        val name: String,
    ) : ItemMutationEvent

    data class AbandonRename(
        val folderId: FilesItemId,
        val intent: FilesFolderOperationIntent.Rename,
    ) : ItemMutationEvent

    sealed interface ItemMutationEvent : FilesBrowserEvent

    sealed interface DeleteEvent : ItemMutationEvent

    data class Delete(
        val folderId: FilesItemId,
        val itemId: FilesItemId,
        val mode: FilesDeleteMode,
    ) : DeleteEvent

    data class DeleteFinished(
        val requestId: FilesRequestId,
        val result: FilesRepositoryResult<FileDeleteResult>,
    ) : DeleteEvent

    data class DeleteChecked(
        val requestId: FilesRequestId,
        val result: FilesRepositoryResult<FilesItem>,
    ) : DeleteEvent

    /**
     * The screen announced this settled outcome on its own, so it is not announced again. An
     * outcome a later page corrected is no longer the one announced and is left as it is.
     */
    data class DeleteOutcomeAnnounced(
        val outcome: FilesDeleteOutcome,
    ) : DeleteEvent

    sealed interface MoveEvent : ItemMutationEvent

    data class Move(
        val folderId: FilesItemId,
        val itemId: FilesItemId,
        val destinationId: FilesItemId,
    ) : MoveEvent

    data class MoveFinished(
        val requestId: FilesRequestId,
        val result: FilesRepositoryResult<List<FileMoveError>>,
    ) : MoveEvent

    data class MoveChecked(
        val requestId: FilesRequestId,
        val result: FilesRepositoryResult<FilesItem>,
    ) : MoveEvent

    /** Copies are put.io's background work, so they live beside the folder stack, not on a folder. */
    sealed interface CopyEvent : ItemMutationEvent

    /** Copies [itemId], shared with the viewer and listed in [folderId], into [destination]. */
    data class Copy(
        val folderId: FilesItemId,
        val itemId: FilesItemId,
        val destination: FilesFolder,
    ) : CopyEvent

    data class CopyStarted(
        val requestId: FilesRequestId,
        val result: FilesRepositoryResult<FilesCopyId>,
    ) : CopyEvent

    data class CopyChecked(
        val requestId: FilesRequestId,
        val result: FilesRepositoryResult<FilesCopyProgress>,
    ) : CopyEvent

    /** Clears a settled copy's status line; a running copy keeps it. */
    data object DismissCopyOutcome : CopyEvent

    data object Retry : FilesBrowserEvent

    data class ViewportChanged(
        val position: FilesViewportPosition,
    ) : FilesBrowserEvent

    sealed interface LoadResult : FilesBrowserEvent {
        val requestId: FilesRequestId
    }

    data class LoadSucceeded(
        override val requestId: FilesRequestId,
        val page: FilesPage,
    ) : LoadResult

    data class LoadFailed(
        override val requestId: FilesRequestId,
        val failure: FilesFailure,
    ) : LoadResult

    data class MutationSucceeded(
        val requestId: FilesRequestId,
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

    data class PersistSort(
        val folderId: FilesItemId,
        val sort: FilesSort,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    data class Rename(
        val itemId: FilesItemId,
        val name: String,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    data class Delete(
        val itemId: FilesItemId,
        val mode: FilesDeleteMode,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    data class Move(
        val itemId: FilesItemId,
        val destinationId: FilesItemId,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    data class CheckMove(
        val itemId: FilesItemId,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    data class CheckDelete(
        val itemId: FilesItemId,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    sealed interface CopyEffect : FilesBrowserEffect

    data class StartCopy(
        val itemId: FilesItemId,
        val destinationId: FilesItemId,
        override val requestId: FilesRequestId,
    ) : CopyEffect

    /** Waits [COPY_CHECK_INTERVAL_MILLIS] before asking, as web does between checks. */
    data class CheckCopy(
        val copyId: FilesCopyId,
        override val requestId: FilesRequestId,
    ) : CopyEffect
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
            is FilesBrowserEvent.OpenExternalItem -> state.openExternalItem(event.item, event.origin)
            FilesBrowserEvent.NavigateBack -> state.navigateBack()
            FilesBrowserEvent.LoadNextPage -> state.loadNextPage()
            is FilesBrowserEvent.RevealItem -> state.revealItem(event.folderId, event.itemId)
            FilesBrowserEvent.Refresh -> state.refresh()
            is FilesBrowserEvent.InvalidationEvent -> state.invalidation(event)
            is FilesBrowserEvent.SelectSort -> state.selectSort(event.sort)
            is FilesBrowserEvent.ItemMutationEvent -> state.itemMutation(event)
            FilesBrowserEvent.Retry -> state.retry()
            is FilesBrowserEvent.ViewportChanged -> state.rememberViewport(event.position)
            is FilesBrowserEvent.LoadResult -> state.loadResult(event)
            is FilesBrowserEvent.MutationSucceeded -> state.mutationSucceeded(event.requestId)
        }

    private fun FilesBrowserState.invalidation(event: FilesBrowserEvent.InvalidationEvent): FilesBrowserTransition =
        when (event) {
            is FilesBrowserEvent.InvalidateRestoredItem -> invalidateRestoredItem(event.item)
            FilesBrowserEvent.InvalidateAllFolders -> invalidateAllFolders()
            FilesBrowserEvent.InvalidateSortOrder -> invalidateSortOrder()
            FilesBrowserEvent.ReloadIfStale -> reloadIfStale()
            is FilesBrowserEvent.PlaybackPositionReported -> updatePlaybackPosition(event.itemId, event.seconds)
        }

    private fun FilesBrowserState.itemMutation(event: FilesBrowserEvent.ItemMutationEvent): FilesBrowserTransition =
        when (event) {
            is FilesBrowserEvent.Rename -> rename(event)
            is FilesBrowserEvent.AbandonRename -> abandonRename(event)
            is FilesBrowserEvent.DeleteEvent -> reduceDelete(event)
            is FilesBrowserEvent.MoveEvent -> reduceMove(event)
            is FilesBrowserEvent.CopyEvent -> reduceCopy(event)
        }

    private fun FilesBrowserState.loadResult(event: FilesBrowserEvent.LoadResult): FilesBrowserTransition =
        when (event) {
            is FilesBrowserEvent.LoadSucceeded -> loadSucceeded(event)
            is FilesBrowserEvent.LoadFailed -> loadFailed(event)
        }
}

suspend fun FilesRepository.execute(effect: FilesBrowserEffect): FilesBrowserEvent =
    when (effect) {
        is FilesBrowserEffect.LoadFolder -> loadFolder(effect.folderId).toLoadEvent(effect.requestId)
        is FilesBrowserEffect.LoadNextPage -> loadNextPage(effect.cursor).toLoadEvent(effect.requestId)
        is FilesBrowserEffect.Move ->
            FilesBrowserEvent.MoveFinished(effect.requestId, move(effect.itemId, effect.destinationId))
        is FilesBrowserEffect.CheckMove ->
            FilesBrowserEvent.MoveChecked(effect.requestId, resolveItem(effect.itemId))
        is FilesBrowserEffect.Delete ->
            FilesBrowserEvent.DeleteFinished(effect.requestId, delete(effect.itemId, effect.mode))
        is FilesBrowserEffect.CheckDelete ->
            FilesBrowserEvent.DeleteChecked(effect.requestId, resolveItem(effect.itemId))
        is FilesBrowserEffect.CopyEffect -> executeCopy(effect)
        is FilesBrowserEffect.Rename ->
            when (val renamed = rename(effect.itemId, effect.name)) {
                is FilesRepositoryResult.Success -> FilesBrowserEvent.MutationSucceeded(effect.requestId)
                is FilesRepositoryResult.Failure -> FilesBrowserEvent.LoadFailed(effect.requestId, renamed.failure)
            }
        is FilesBrowserEffect.PersistSort ->
            when (val persisted = persistSort(effect.folderId, effect.sort)) {
                is FilesRepositoryResult.Success -> FilesBrowserEvent.MutationSucceeded(effect.requestId)
                is FilesRepositoryResult.Failure -> FilesBrowserEvent.LoadFailed(effect.requestId, persisted.failure)
            }
    }

private fun FilesRepositoryResult<FilesPage>.toLoadEvent(requestId: FilesRequestId): FilesBrowserEvent =
    when (this) {
        is FilesRepositoryResult.Success -> FilesBrowserEvent.LoadSucceeded(requestId, value)
        is FilesRepositoryResult.Failure -> FilesBrowserEvent.LoadFailed(requestId, failure)
    }

private const val INITIAL_REQUEST_VALUE = 1L
