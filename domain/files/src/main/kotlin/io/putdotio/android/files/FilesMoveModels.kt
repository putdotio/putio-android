package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.files.FileMoveError

public enum class FilesMoveStatus {
    CHECKING,
    MOVED,
    STILL_PRESENT,
    REJECTED,
    UNKNOWN,
}

public data class FilesMoveOutcome(
    val requestId: FilesRequestId,
    val intent: FilesFolderOperationIntent.Move,
    /** The first item's name; a move of several names them by count. */
    val itemName: String,
    val errors: List<FileMoveError>? = null,
    val failure: PutioFailure? = null,
    val status: FilesMoveStatus = FilesMoveStatus.CHECKING,
    /** How many of the items the check found outside the destination. */
    val remainingCount: Int = 0,
) {
    val itemCount: Int get() = intent.itemIds.size
}
