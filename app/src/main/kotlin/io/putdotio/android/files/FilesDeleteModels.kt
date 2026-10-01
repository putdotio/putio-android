package io.putdotio.android.files

import io.putdotio.sdk.files.FileDeleteResult

enum class FilesDeleteMode {
    TRASH,
    PERMANENT,
}

enum class FilesDeleteStatus {
    CHECKING,
    NO_LONGER_AVAILABLE,
    STILL_PRESENT,
    SKIPPED,
    UNKNOWN,
}

data class FilesDeleteOutcome(
    val requestId: FilesRequestId,
    val intent: FilesFolderOperationIntent.Delete,
    val itemName: String,
    val response: FileDeleteResult? = null,
    val failure: FilesFailure? = null,
    val status: FilesDeleteStatus = FilesDeleteStatus.CHECKING,
    /** The screen announced it on its own; it stays for a later page to correct. */
    val announced: Boolean = false,
)
