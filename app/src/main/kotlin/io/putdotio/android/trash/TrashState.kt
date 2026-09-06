package io.putdotio.android.trash

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.errors.PutioException

sealed interface TrashContent {
    data object Loading : TrashContent
    data class Error(val failure: FilesFailure) : TrashContent
    data class Loaded(
        val items: List<TrashItem>,
        val nextCursor: FilesCursor?,
        val total: Int?,
        val trashSizeBytes: Long?,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val refreshFailure: FilesFailure? = null,
        val pageFailure: FilesFailure? = null,
        // The initial page's cursor names every Trash ID of that snapshot; bulk Restore all reuses it.
        val snapshotCursor: FilesCursor? = null,
    ) : TrashContent {
        val isBusy: Boolean get() = isRefreshing || isLoadingMore
        val isKnownEmpty: Boolean get() = items.isEmpty() && nextCursor == null
    }
}

sealed interface TrashAction {
    data class DeleteItem(val item: TrashItem) : TrashAction
    data object RestoreAll : TrashAction
    data object Empty : TrashAction
}

enum class TrashActionSubmission { SUBMITTING, ACKNOWLEDGED, UNCERTAIN, REJECTED }

/** Verification is one authoritative Trash reload; INCONCLUSIVE keeps read-only recovery open. */
enum class TrashActionCheck { NOT_CHECKED, CHECKING, VERIFIED, INCONCLUSIVE, FAILED }

data class TrashActionOutcome(
    val action: TrashAction,
    val submission: TrashActionSubmission,
    val check: TrashActionCheck = TrashActionCheck.NOT_CHECKED,
    val submissionFailure: FilesFailure? = null,
    val checkFailure: FilesFailure? = null,
) {
    val isPending: Boolean get() = submission != TrashActionSubmission.REJECTED && check != TrashActionCheck.VERIFIED
}

enum class TrashRestoreSubmission { SUBMITTING, ACKNOWLEDGED, UNCERTAIN, REJECTED }
enum class TrashRestoreCheck { NOT_CHECKED, CHECKING, UNAVAILABLE, AVAILABLE, FAILED }

data class TrashRestoreOutcome(
    val item: TrashItem,
    val submission: TrashRestoreSubmission,
    val check: TrashRestoreCheck = TrashRestoreCheck.NOT_CHECKED,
    val submissionFailure: FilesFailure? = null,
    val checkFailure: FilesFailure? = null,
    val resolvedItem: FilesItem? = null,
) {
    val isPending: Boolean get() = submission != TrashRestoreSubmission.REJECTED && check != TrashRestoreCheck.AVAILABLE
}

data class TrashState(
    val content: TrashContent = TrashContent.Loading,
    val confirmation: TrashItem? = null,
    val confirmationId: Long? = null,
    val restoreOutcome: TrashRestoreOutcome? = null,
    val actionConfirmation: TrashAction? = null,
    val actionConfirmationId: Long? = null,
    val actionOutcome: TrashActionOutcome? = null,
    val authenticationFailure: PutioException? = null,
    val restoredVersion: Long = 0L,
    val restoredItemIds: Set<FilesItemId> = emptySet(),
    internal val restoredOccurrences: Map<FilesItemId, String?> = emptyMap(),
    val lastRestoredItem: FilesItem? = null,
    // Bumps when a bulk restore is acknowledged or verified; every cached Files folder may have changed.
    val bulkRestoreVersion: Long = 0L,
) {
    val hasPendingRestore: Boolean get() = restoreOutcome?.isPending == true
    val hasPendingAction: Boolean get() = actionOutcome?.isPending == true
    val hasPendingMutation: Boolean get() = hasPendingRestore || hasPendingAction
    private val isIdleLoaded: Boolean
        get() = content is TrashContent.Loaded && !content.isBusy &&
            authenticationFailure == null && !hasPendingMutation
    fun canRestore(itemId: FilesItemId): Boolean =
        isIdleLoaded && itemId.value > 0L && itemId !in restoredItemIds
    fun canDelete(itemId: FilesItemId): Boolean = isIdleLoaded && itemId.value > 0L
    val canActOnAll: Boolean get() = isIdleLoaded && !(content as TrashContent.Loaded).isKnownEmpty
}

sealed interface TrashEvent {
    sealed interface ReadEvent : TrashEvent
    sealed interface RestoreEvent : TrashEvent
    sealed interface ActionEvent : TrashEvent

    data object Open : ReadEvent
    data object Refresh : ReadEvent
    data object Retry : ReadEvent
    data object LoadNextPage : ReadEvent
    data class SelectRestore(val itemId: FilesItemId) : RestoreEvent
    data object CancelRestore : RestoreEvent
    data class ConfirmRestore(val confirmationId: Long) : RestoreEvent
    data object CheckRestore : RestoreEvent
    data object DismissRestoreOutcome : RestoreEvent
    data class SelectDelete(val itemId: FilesItemId) : ActionEvent
    data object SelectRestoreAll : ActionEvent
    data object SelectEmpty : ActionEvent
    data object CancelAction : ActionEvent
    data class ConfirmAction(val confirmationId: Long) : ActionEvent
    data object CheckAction : ActionEvent
    data object DismissActionOutcome : ActionEvent
}

internal sealed interface TrashRequest {
    val id: Long
    data class ListPage(
        override val id: Long,
        val cursor: FilesCursor? = null,
        val verifiesAction: Boolean = false,
    ) : TrashRequest
    data class Restore(override val id: Long, val item: TrashItem) : TrashRequest
    data class Check(override val id: Long, val item: TrashItem) : TrashRequest
    data class Act(override val id: Long, val action: TrashAction, val selection: TrashBulkSelection?) : TrashRequest
}

/** Restore all targets the listed snapshot: its cursor when the server issued one, else the loaded IDs. */
data class TrashBulkSelection(val cursor: FilesCursor?, val itemIds: List<FilesItemId>) {
    init {
        require(cursor != null || itemIds.isNotEmpty()) { "Bulk selection needs a cursor or item IDs" }
    }
}

internal data class TrashMachine(
    val state: TrashState = TrashState(),
    val request: TrashRequest? = null,
    val nextRequestId: Long = 1L,
    val opened: Boolean = false,
    val consumedCursors: Set<FilesCursor> = emptySet(),
)
