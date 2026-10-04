package io.putdotio.android.trash

import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.errors.PutioException
import java.time.Instant

public sealed interface TrashContent {
    public data object Loading : TrashContent
    public data class Error(val failure: PutioFailure) : TrashContent
    public data class Loaded(
        val items: List<TrashItem>,
        val nextCursor: FilesCursor?,
        val total: Int?,
        val trashSizeBytes: Long?,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val refreshFailure: PutioFailure? = null,
        val pageFailure: PutioFailure? = null,
        // The initial page's cursor names every Trash ID of that snapshot; bulk Restore all reuses it.
        val snapshotCursor: FilesCursor? = null,
    ) : TrashContent {
        val isBusy: Boolean get() = isRefreshing || isLoadingMore
        val isKnownEmpty: Boolean get() = items.isEmpty() && nextCursor == null
    }
}

public sealed interface TrashAction {
    public data class DeleteItem(val item: TrashItem) : TrashAction
    public data object RestoreAll : TrashAction
    public data object Empty : TrashAction
}

public enum class TrashActionSubmission { SUBMITTING, ACKNOWLEDGED, UNCERTAIN, REJECTED }

/** Verification is one authoritative Trash reload; INCONCLUSIVE keeps read-only recovery open. */
public enum class TrashActionCheck { NOT_CHECKED, CHECKING, VERIFIED, INCONCLUSIVE, FAILED }

public data class TrashActionOutcome(
    val action: TrashAction,
    val submission: TrashActionSubmission,
    val check: TrashActionCheck = TrashActionCheck.NOT_CHECKED,
    val submissionFailure: PutioFailure? = null,
    val checkFailure: PutioFailure? = null,
    // What Restore all submitted; verification compares the fresh listing against it, not against empty.
    val restoreSnapshot: TrashRestoreSnapshot? = null,
) {
    // A complete read that still lists the target answers the question; only a failed read keeps recovery open.
    val isPending: Boolean
        get() = submission != TrashActionSubmission.REJECTED && check != TrashActionCheck.VERIFIED &&
            !(check == TrashActionCheck.FAILED && checkFailure == null)
}

public data class TrashRestoreSnapshot(
    val itemIds: Set<FilesItemId>,
    // A cursor covers IDs beyond the loaded ones; anything deleted after the newest loaded row is outside it.
    val coversUnloadedItems: Boolean,
    val newestDeletedAt: Instant?,
)

public enum class TrashRestoreSubmission { SUBMITTING, ACKNOWLEDGED, UNCERTAIN, REJECTED }
public enum class TrashRestoreCheck { NOT_CHECKED, CHECKING, UNAVAILABLE, AVAILABLE, FAILED }

public data class TrashRestoreOutcome(
    val item: TrashItem,
    val submission: TrashRestoreSubmission,
    val check: TrashRestoreCheck = TrashRestoreCheck.NOT_CHECKED,
    val submissionFailure: PutioFailure? = null,
    val checkFailure: PutioFailure? = null,
    val resolvedItem: FilesItem? = null,
) {
    val isPending: Boolean get() = submission != TrashRestoreSubmission.REJECTED && check != TrashRestoreCheck.AVAILABLE
}

public data class TrashState(
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
    public fun canRestore(itemId: FilesItemId): Boolean =
        isIdleLoaded && itemId.value > 0L && itemId !in restoredItemIds
    public fun canDelete(itemId: FilesItemId): Boolean = isIdleLoaded && itemId.value > 0L
    // A failed refresh leaves a snapshot the server may have moved past; bulk actions wait for a fresh one.
    val canActOnAll: Boolean
        get() = isIdleLoaded && (content as TrashContent.Loaded).let { !it.isKnownEmpty && it.refreshFailure == null }
}

public sealed interface TrashEvent {
    public sealed interface ReadEvent : TrashEvent
    public sealed interface RestoreEvent : TrashEvent
    public sealed interface ActionEvent : TrashEvent

    public data object Open : ReadEvent
    public data object Refresh : ReadEvent
    public data object Retry : ReadEvent
    public data object LoadNextPage : ReadEvent
    public data class SelectRestore(val itemId: FilesItemId) : RestoreEvent
    public data object CancelRestore : RestoreEvent
    public data class ConfirmRestore(val confirmationId: Long) : RestoreEvent
    public data object CheckRestore : RestoreEvent
    public data object DismissRestoreOutcome : RestoreEvent
    public data class SelectDelete(val itemId: FilesItemId) : ActionEvent
    public data object SelectRestoreAll : ActionEvent
    public data object SelectEmpty : ActionEvent
    public data object CancelAction : ActionEvent
    public data class ConfirmAction(val confirmationId: Long) : ActionEvent
    public data object CheckAction : ActionEvent
    public data object DismissActionOutcome : ActionEvent
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
public data class TrashBulkSelection(val cursor: FilesCursor?, val itemIds: List<FilesItemId>) {
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
