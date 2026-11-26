package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.files.FileDeleteResult

public enum class FilesDeleteMode {
    TRASH,
    PERMANENT,
}

public enum class FilesDeleteStatus {
    CHECKING,
    NO_LONGER_AVAILABLE,
    STILL_PRESENT,

    TOO_LARGE_FOR_TRASH,
    SKIPPED,
    UNKNOWN,
}

public data class FilesDeleteOutcome(
    val requestId: FilesRequestId,
    val intent: FilesFolderOperationIntent.Delete,
    val itemName: String,
    val response: FileDeleteResult? = null,
    val failure: PutioFailure? = null,
    val status: FilesDeleteStatus = FilesDeleteStatus.CHECKING,
    val announced: Boolean = false,
    val remainingIds: Set<FilesItemId> = emptySet(),
) {
    val itemCount: Int get() = intent.itemIds.size
    val remainingCount: Int get() = remainingIds.size
}

internal val PutioFailure?.isTrashChildrenLimit: Boolean
    get() = this is PutioFailure.ApiRejected && errorType == "FileDeleteChildrenLimitError"

public fun FilesDeleteOutcome.retry(folder: FilesFolderState, mode: FilesDeleteMode?): FilesBrowserEvent.Delete? {
    if (mode != intent.mode || (status != FilesDeleteStatus.STILL_PRESENT && status != FilesDeleteStatus.SKIPPED)) {
        return null
    }
    return failedListedIds(intent.itemIds, remainingIds, folder)
        ?.let { FilesBrowserEvent.Delete(folder.folder.id, it, mode) }
}

internal fun failedListedIds(
    itemIds: List<FilesItemId>,
    remainingIds: Set<FilesItemId>,
    folder: FilesFolderState,
): List<FilesItemId>? {
    val retryable = itemIds.size >= 2 && remainingIds.isNotEmpty() &&
        folder.operation == FilesFolderOperation.Idle &&
        folder.content.items().mapTo(mutableSetOf()) { it.id }.containsAll(remainingIds)
    return if (retryable) itemIds.filter { it in remainingIds } else null
}
