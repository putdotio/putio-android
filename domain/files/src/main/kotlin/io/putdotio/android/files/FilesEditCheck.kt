package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.isNotFound
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FileMoveError

public sealed interface FilesEditCheck<out O> {
    public data class Settled<O>(val outcome: O) : FilesEditCheck<O>

    public data class Unconfirmed(val failure: PutioFailure) : FilesEditCheck<Nothing>
}

public fun FilesMoveOutcome.recording(result: PutioResult<List<FileMoveError>>): FilesMoveOutcome = copy(
    errors = (result as? PutioResult.Success)?.value,
    failure = (result as? PutioResult.Failure)?.failure,
)

public fun FilesMoveOutcome.checked(results: List<PutioResult<FilesItem>>): FilesEditCheck<FilesMoveOutcome> {
    val reads = intent.itemIds.zipReads(results)
    val failure = reads.mapNotNull { (id, result) -> result.moveReadFailure(id) }.verdict()
    if (failure != null) return FilesEditCheck.Unconfirmed(failure)
    val notMoved = reads.filter { (_, result) ->
        (result as? PutioResult.Success)?.value?.parentId != intent.destinationId
    }.mapTo(mutableSetOf()) { it.first }
    val status = when {
        !errors.isNullOrEmpty() -> FilesMoveStatus.REJECTED
        notMoved.isEmpty() -> FilesMoveStatus.MOVED
        else -> FilesMoveStatus.STILL_PRESENT
    }
    return FilesEditCheck.Settled(copy(status = status, remainingIds = notMoved))
}

private fun PutioResult<FilesItem>.moveReadFailure(expectedId: FilesItemId): PutioFailure? = when (this) {
    is PutioResult.Failure -> failure
    is PutioResult.Success -> if (value.id == expectedId && (value.parentId?.value ?: -1L) >= 0L) {
        null
    } else {
        PutioFailure.Unexpected(IllegalStateException("Move lookup returned a different ID or no valid parent"))
    }
}

public fun FilesDeleteOutcome.recording(result: PutioResult<FileDeleteResult>): FilesDeleteOutcome = copy(
    response = (result as? PutioResult.Success)?.value,
    failure = (result as? PutioResult.Failure)?.failure,
)

public fun FilesDeleteOutcome.checked(results: List<PutioResult<FilesItem>>): FilesEditCheck<FilesDeleteOutcome> {
    val reads = intent.itemIds.zipReads(results)
        .map { (id, result) -> id to result.deleteReadFailure(id) }
    val failure = reads.mapNotNull { (_, failure) -> failure?.takeUnless { it.isNotFound } }.verdict()
    if (failure != null) return FilesEditCheck.Unconfirmed(failure)
    val remaining = reads.filterNot { (_, failure) -> failure.isNotFound }.mapTo(mutableSetOf()) { it.first }
    return FilesEditCheck.Settled(
        copy(status = checkedStatus(unavailable = remaining.isEmpty()), remainingIds = remaining),
    )
}

private fun PutioResult<FilesItem>.deleteReadFailure(expectedId: FilesItemId): PutioFailure? = when (this) {
    is PutioResult.Failure -> failure
    is PutioResult.Success -> if (value.id == expectedId) null else
        PutioFailure.Unexpected(IllegalStateException("File lookup returned a different ID"))
}

private fun FilesDeleteOutcome.checkedStatus(unavailable: Boolean): FilesDeleteStatus = when {
    response?.let { it.skipped > 0 || it.cursor != null } == true -> FilesDeleteStatus.SKIPPED
    unavailable -> FilesDeleteStatus.NO_LONGER_AVAILABLE
    intent.mode == FilesDeleteMode.TRASH && failure.isTrashChildrenLimit -> FilesDeleteStatus.TOO_LARGE_FOR_TRASH
    else -> FilesDeleteStatus.STILL_PRESENT
}
