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
    val itemName: String,
    val errors: List<FileMoveError>? = null,
    val failure: PutioFailure? = null,
    val status: FilesMoveStatus = FilesMoveStatus.CHECKING,
    val remainingIds: Set<FilesItemId> = emptySet(),
) {
    val itemCount: Int get() = intent.itemIds.size
    val remainingCount: Int get() = remainingIds.size
}

public fun FilesMoveOutcome.retry(state: FilesBrowserState): FilesBrowserEvent.Move? {
    if (status != FilesMoveStatus.STILL_PRESENT || !state.canStartMove) return null
    return failedListedIds(intent.itemIds, remainingIds, state.current)
        ?.let { FilesBrowserEvent.Move(state.current.folder.id, it, intent.destinationId) }
}
