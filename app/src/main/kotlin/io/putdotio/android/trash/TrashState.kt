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
    ) : TrashContent
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
    val authenticationFailure: PutioException? = null,
    val restoredVersion: Long = 0L,
    val restoredItemIds: Set<FilesItemId> = emptySet(),
    internal val restoredOccurrences: Map<FilesItemId, String?> = emptyMap(),
    val lastRestoredItem: FilesItem? = null,
) {
    val hasPendingRestore: Boolean get() = restoreOutcome?.isPending == true
    fun canRestore(itemId: FilesItemId): Boolean =
        content is TrashContent.Loaded && !content.isRefreshing && !content.isLoadingMore &&
            itemId.value > 0L && authenticationFailure == null && !hasPendingRestore && itemId !in restoredItemIds
}

sealed interface TrashEvent {
    data object Open : TrashEvent
    data object Refresh : TrashEvent
    data object Retry : TrashEvent
    data object LoadNextPage : TrashEvent
    data class SelectRestore(val itemId: FilesItemId) : TrashEvent
    data object CancelRestore : TrashEvent
    data class ConfirmRestore(val confirmationId: Long) : TrashEvent
    data object CheckRestore : TrashEvent
    data object DismissRestoreOutcome : TrashEvent
}

internal sealed interface TrashRequest {
    val id: Long
    data class ListPage(override val id: Long, val cursor: FilesCursor? = null) : TrashRequest
    data class Restore(override val id: Long, val item: TrashItem) : TrashRequest
    data class Check(override val id: Long, val item: TrashItem) : TrashRequest
}

internal data class TrashMachine(
    val state: TrashState = TrashState(),
    val request: TrashRequest? = null,
    val nextRequestId: Long = 1L,
    val opened: Boolean = false,
    val consumedCursors: Set<FilesCursor> = emptySet(),
)
