package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FileMoveError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@JvmInline
public value class FilesRequestId(
    public val value: Long,
)

public data class FilesViewportPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)

public sealed interface FilesPaging {
    public data object Complete : FilesPaging

    public data class Available(
        val cursor: FilesCursor,
    ) : FilesPaging

    public data class Loading(
        val cursor: FilesCursor,
        val requestId: FilesRequestId,
    ) : FilesPaging

    public data class Failed(
        val cursor: FilesCursor,
        val failure: PutioFailure,
    ) : FilesPaging
}

public sealed interface FilesContent {
    public data class Loading(
        val requestId: FilesRequestId,
    ) : FilesContent

    public data class Empty(
        val paging: FilesPaging,
        val viewport: FilesViewportPosition = FilesViewportPosition(),
    ) : FilesContent

    public data class Ready(
        val items: List<FilesItem>,
        val paging: FilesPaging,
        val viewport: FilesViewportPosition = FilesViewportPosition(),
    ) : FilesContent {
        init {
            require(items.isNotEmpty()) { "Ready Files content must contain at least one item" }
        }
    }

    public data class Failed(
        val failure: PutioFailure,
    ) : FilesContent
}

public sealed interface FilesFolderOperationIntent {
    public data object Refresh : FilesFolderOperationIntent

    public data class Sort(
        val sort: FilesSort,
    ) : FilesFolderOperationIntent

    public data class Rename(
        val itemId: FilesItemId,
        val name: String,
    ) : FilesFolderOperationIntent

    /** Moves [itemIds], all listed in the folder, into [destinationId] in one request. */
    public data class Move(
        val itemIds: List<FilesItemId>,
        val destinationId: FilesItemId,
    ) : FilesFolderOperationIntent {
        public constructor(itemId: FilesItemId, destinationId: FilesItemId) : this(listOf(itemId), destinationId)

        init {
            require(itemIds.isNotEmpty()) { "A move needs at least one item" }
        }
    }

    /** Deletes [itemIds], all listed in the folder, in one request. */
    public data class Delete(
        val itemIds: List<FilesItemId>,
        val mode: FilesDeleteMode,
    ) : FilesFolderOperationIntent {
        public constructor(itemId: FilesItemId, mode: FilesDeleteMode) : this(listOf(itemId), mode)

        init {
            require(itemIds.isNotEmpty()) { "A delete needs at least one item" }
        }
    }
}

public enum class FilesFolderOperationPhase {
    PERSISTING_SORT,
    RENAMING,
    DELETING,
    CHECKING_DELETE,
    MOVING,
    CHECKING_MOVE,
    RELOADING,
}

public sealed interface FilesFolderOperation {
    public data object Idle : FilesFolderOperation

    public data class Loading(
        val requestId: FilesRequestId,
        val intent: FilesFolderOperationIntent,
        val phase: FilesFolderOperationPhase,
    ) : FilesFolderOperation

    public data class Failed(
        val failure: PutioFailure,
        val intent: FilesFolderOperationIntent,
        val phase: FilesFolderOperationPhase,
    ) : FilesFolderOperation
}

public data class FilesRenameCompletion(
    val requestId: FilesRequestId,
    val intent: FilesFolderOperationIntent.Rename,
)

public data class FilesFolderState(
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
    val needsReload: Boolean = false,
    /** The folder's count and size from its latest first page; null until one has loaded. */
    val summary: FilesFolderSummary? = null,
    internal val consumedCursors: Set<FilesCursor> = emptySet(),
)

@ConsistentCopyVisibility
public data class FilesBrowserState internal constructor(
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

public sealed interface FilesBrowserEvent {
    public data class OpenFolder(
        val itemId: FilesItemId,
    ) : FilesBrowserEvent

    /** A folder opens itself and any other item its parent, on top of the current location. */
    public data class OpenExternalItem(
        val item: FilesItem,
        val origin: FilesOpenOrigin,
    ) : FilesBrowserEvent

    public data object NavigateBack : FilesBrowserEvent

    public data object LoadNextPage : FilesBrowserEvent

    /** Shows [itemId] when [folderId] is the current folder, reading later pages for it when needed. */
    public data class RevealItem(
        val folderId: FilesItemId,
        val itemId: FilesItemId,
    ) : FilesBrowserEvent

    public data object Refresh : FilesBrowserEvent

    /** Staleness signals from other surfaces; they never navigate or mutate on their own. */
    public sealed interface InvalidationEvent : FilesBrowserEvent

    public data class InvalidateRestoredItem(val item: FilesItem) : InvalidationEvent

    public data object InvalidateAllFolders : InvalidationEvent

    /** The account default order changed; every cached listing may now be in the wrong order. */
    public data object InvalidateSortOrder : InvalidationEvent

    public data object ReloadIfStale : InvalidationEvent

    /** A saved position for [itemId]; cached rows update in place so leaving playback shows the new progress. */
    public data class PlaybackPositionReported(val itemId: FilesItemId, val seconds: Double) : InvalidationEvent

    public data class SelectSort(
        val sort: FilesSort,
    ) : FilesBrowserEvent

    public data class Rename(
        val folderId: FilesItemId,
        val itemId: FilesItemId,
        val name: String,
    ) : ItemMutationEvent

    public data class AbandonRename(
        val folderId: FilesItemId,
        val intent: FilesFolderOperationIntent.Rename,
    ) : ItemMutationEvent

    public sealed interface ItemMutationEvent : FilesBrowserEvent

    public sealed interface DeleteEvent : ItemMutationEvent

    /** Deletes [itemIds] from [folderId], which must be the current folder and list each of them. */
    public data class Delete(
        val folderId: FilesItemId,
        val itemIds: List<FilesItemId>,
        val mode: FilesDeleteMode,
    ) : DeleteEvent {
        public constructor(folderId: FilesItemId, itemId: FilesItemId, mode: FilesDeleteMode) :
            this(folderId, listOf(itemId), mode)
    }

    public data class DeleteFinished(
        val requestId: FilesRequestId,
        val result: PutioResult<FileDeleteResult>,
    ) : DeleteEvent

    /** One read per deleted item, in the delete's order. */
    public data class DeleteChecked(
        val requestId: FilesRequestId,
        val results: List<PutioResult<FilesItem>>,
    ) : DeleteEvent {
        public constructor(requestId: FilesRequestId, result: PutioResult<FilesItem>) :
            this(requestId, listOf(result))
    }

    /**
     * The screen announced this settled outcome on its own, so it is not announced again. An
     * outcome a later page corrected is no longer the one announced and is left as it is.
     */
    public data class DeleteOutcomeAnnounced(
        val outcome: FilesDeleteOutcome,
    ) : DeleteEvent

    /**
     * put.io accepted restoring this Trash move from Trash: the outcome leaves its folder, so a page
     * that lists the item again is not read as a failed delete, and the folder reads again.
     */
    public data class TrashUndone(
        val outcome: FilesDeleteOutcome,
    ) : DeleteEvent

    public sealed interface MoveEvent : ItemMutationEvent

    /** Moves [itemIds] from [folderId], which must be the current folder and list each of them. */
    public data class Move(
        val folderId: FilesItemId,
        val itemIds: List<FilesItemId>,
        val destinationId: FilesItemId,
    ) : MoveEvent {
        public constructor(folderId: FilesItemId, itemId: FilesItemId, destinationId: FilesItemId) :
            this(folderId, listOf(itemId), destinationId)
    }

    public data class MoveFinished(
        val requestId: FilesRequestId,
        val result: PutioResult<List<FileMoveError>>,
    ) : MoveEvent

    /** One read per moved item, in the move's order. */
    public data class MoveChecked(
        val requestId: FilesRequestId,
        val results: List<PutioResult<FilesItem>>,
    ) : MoveEvent {
        public constructor(requestId: FilesRequestId, result: PutioResult<FilesItem>) :
            this(requestId, listOf(result))
    }

    /** Copies are put.io's background work, so they live beside the folder stack, not on a folder. */
    public sealed interface CopyEvent : ItemMutationEvent

    /** Copies [itemId], shared with the viewer and listed in [folderId], into [destination]. */
    public data class Copy(
        val folderId: FilesItemId,
        val itemId: FilesItemId,
        val destination: FilesFolder,
    ) : CopyEvent

    public data class CopyStarted(
        val requestId: FilesRequestId,
        val result: PutioResult<FilesCopyId>,
    ) : CopyEvent

    public data class CopyChecked(
        val requestId: FilesRequestId,
        val result: PutioResult<FilesCopyProgress>,
    ) : CopyEvent

    /** Clears a settled copy's status line; a running copy keeps it. */
    public data object DismissCopyOutcome : CopyEvent

    public data object Retry : FilesBrowserEvent

    public data class ViewportChanged(
        val position: FilesViewportPosition,
    ) : FilesBrowserEvent

    public sealed interface LoadResult : FilesBrowserEvent {
        public val requestId: FilesRequestId
    }

    public data class LoadSucceeded(
        override val requestId: FilesRequestId,
        val page: FilesPage,
    ) : LoadResult

    public data class LoadFailed(
        override val requestId: FilesRequestId,
        val failure: PutioFailure,
    ) : LoadResult

    public data class MutationSucceeded(
        val requestId: FilesRequestId,
    ) : FilesBrowserEvent
}

public sealed interface FilesBrowserEffect {
    public val requestId: FilesRequestId

    public data class LoadFolder(
        val folderId: FilesItemId,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    public data class LoadNextPage(
        val cursor: FilesCursor,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    public data class PersistSort(
        val folderId: FilesItemId,
        val sort: FilesSort,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    public data class Rename(
        val itemId: FilesItemId,
        val name: String,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    public data class Delete(
        val itemIds: List<FilesItemId>,
        val mode: FilesDeleteMode,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    public data class Move(
        val itemIds: List<FilesItemId>,
        val destinationId: FilesItemId,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    /** Reads each moved item back, to learn where it is now. */
    public data class CheckMove(
        val itemIds: List<FilesItemId>,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    /** Reads each deleted item back, to learn whether it is gone. */
    public data class CheckDelete(
        val itemIds: List<FilesItemId>,
        override val requestId: FilesRequestId,
    ) : FilesBrowserEffect

    public sealed interface CopyEffect : FilesBrowserEffect

    public data class StartCopy(
        val itemId: FilesItemId,
        val destinationId: FilesItemId,
        override val requestId: FilesRequestId,
    ) : CopyEffect

    /** Waits [COPY_CHECK_INTERVAL_MILLIS] before asking, as web does between checks. */
    public data class CheckCopy(
        val copyId: FilesCopyId,
        override val requestId: FilesRequestId,
    ) : CopyEffect
}

public data class FilesBrowserTransition(
    val state: FilesBrowserState,
    val effect: FilesBrowserEffect? = null,
    val consumed: Boolean = true,
)

public object FilesBrowserReducer {
    public fun start(): FilesBrowserTransition {
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

    public fun reduce(
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

internal suspend fun FilesRepository.execute(effect: FilesBrowserEffect): FilesBrowserEvent =
    when (effect) {
        is FilesBrowserEffect.LoadFolder -> loadFolder(effect.folderId).toLoadEvent(effect.requestId)
        is FilesBrowserEffect.LoadNextPage -> loadNextPage(effect.cursor).toLoadEvent(effect.requestId)
        is FilesBrowserEffect.Move ->
            FilesBrowserEvent.MoveFinished(effect.requestId, move(effect.itemIds, effect.destinationId))
        is FilesBrowserEffect.CheckMove ->
            FilesBrowserEvent.MoveChecked(effect.requestId, resolveItems(effect.itemIds))
        is FilesBrowserEffect.Delete ->
            FilesBrowserEvent.DeleteFinished(effect.requestId, delete(effect.itemIds, effect.mode))
        is FilesBrowserEffect.CheckDelete ->
            FilesBrowserEvent.DeleteChecked(effect.requestId, resolveItems(effect.itemIds))
        is FilesBrowserEffect.CopyEffect -> executeCopy(effect)
        is FilesBrowserEffect.Rename ->
            when (val renamed = rename(effect.itemId, effect.name)) {
                is PutioResult.Success -> FilesBrowserEvent.MutationSucceeded(effect.requestId)
                is PutioResult.Failure -> FilesBrowserEvent.LoadFailed(effect.requestId, renamed.failure)
            }
        is FilesBrowserEffect.PersistSort ->
            when (val persisted = persistSort(effect.folderId, effect.sort)) {
                is PutioResult.Success -> FilesBrowserEvent.MutationSucceeded(effect.requestId)
                is PutioResult.Failure -> FilesBrowserEvent.LoadFailed(effect.requestId, persisted.failure)
            }
    }

/**
 * put.io has no batch read, so each item is read on its own, a few at a time; the results keep the
 * items' order.
 */
private suspend fun FilesRepository.resolveItems(itemIds: List<FilesItemId>): List<PutioResult<FilesItem>> {
    val permits = Semaphore(CHECK_CONCURRENCY)
    return coroutineScope {
        itemIds.map { id -> async { permits.withPermit { resolveItem(id) } } }.awaitAll()
    }
}

private fun PutioResult<FilesPage>.toLoadEvent(requestId: FilesRequestId): FilesBrowserEvent =
    when (this) {
        is PutioResult.Success -> FilesBrowserEvent.LoadSucceeded(requestId, value)
        is PutioResult.Failure -> FilesBrowserEvent.LoadFailed(requestId, failure)
    }

private const val INITIAL_REQUEST_VALUE = 1L
private const val CHECK_CONCURRENCY = 4
