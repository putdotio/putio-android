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
    /** How many of the items the check still found; a later page that lists one counts it too. */
    val remainingCount: Int = 0,
) {
    val itemCount: Int get() = intent.itemIds.size
}

/** put.io refuses a Trash move of a folder over its item limit with this error type, before changing anything. */
internal val PutioFailure?.isTrashChildrenLimit: Boolean
    get() = this is PutioFailure.ApiRejected && errorType == "FileDeleteChildrenLimitError"
