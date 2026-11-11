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

    /** The folder has more items than put.io's Trash takes; only Delete permanently removes it. */
    TOO_LARGE_FOR_TRASH,
    SKIPPED,
    UNKNOWN,
}

public data class FilesDeleteOutcome(
    val requestId: FilesRequestId,
    val intent: FilesFolderOperationIntent.Delete,
    /** The first item's name; a delete of several names them by count. */
    val itemName: String,
    val response: FileDeleteResult? = null,
    val failure: PutioFailure? = null,
    val status: FilesDeleteStatus = FilesDeleteStatus.CHECKING,
    /** The screen announced it on its own; it stays for a later page to correct. */
    val announced: Boolean = false,
    /** The items the check still found, and those a later page lists again. */
    val remainingIds: Set<FilesItemId> = emptySet(),
) {
    val itemCount: Int get() = intent.itemIds.size
    val remainingCount: Int get() = remainingIds.size
}

/** put.io refuses a Trash move of a folder over its item limit with this error type, before changing anything. */
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
    if (itemIds.size < 2 || folder.operation != FilesFolderOperation.Idle) return null
    val listed = folder.content.items().mapTo(mutableSetOf()) { it.id }
    return itemIds.filter { it in remainingIds && it in listed }.takeIf { it.isNotEmpty() }
}
