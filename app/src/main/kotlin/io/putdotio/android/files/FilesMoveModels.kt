package io.putdotio.android.files

import io.putdotio.sdk.files.FileMoveError

enum class FilesMoveStatus {
    CHECKING,
    MOVED,
    STILL_PRESENT,
    REJECTED,
    UNKNOWN,
}

data class FilesMoveOutcome(
    val requestId: FilesRequestId,
    val intent: FilesFolderOperationIntent.Move,
    val itemName: String,
    val errors: List<FileMoveError>? = null,
    val failure: FilesFailure? = null,
    val status: FilesMoveStatus = FilesMoveStatus.CHECKING,
)
