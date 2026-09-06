package io.putdotio.android.trash

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesRepositoryResult

internal fun TrashMachine.completeRestore(
    completed: TrashRequest.Restore,
    result: FilesRepositoryResult<Unit>,
): TrashMachine {
    val outcome = state.restoreOutcome
    if (request != completed || outcome == null) return this
    val failure = (result as? FilesRepositoryResult.Failure)?.failure
    val submission = when {
        failure == null -> TrashRestoreSubmission.ACKNOWLEDGED
        failure.isKnownRestoreRejection() -> TrashRestoreSubmission.REJECTED
        else -> TrashRestoreSubmission.UNCERTAIN
    }
    val next = copy(request = null, state = state.copy(
        restoreOutcome = outcome.copy(submission = submission, submissionFailure = failure),
        authenticationFailure = failure?.authFailure() ?: state.authenticationFailure,
    ))
    return if (submission == TrashRestoreSubmission.REJECTED) next else next.startCheck() ?: next
}

internal fun TrashMachine.completeCheck(
    completed: TrashRequest.Check,
    result: FilesRepositoryResult<FilesItem>,
): TrashMachine {
    val outcome = state.restoreOutcome
    if (request != completed || outcome == null) return this
    val failure = result.readFailure(completed.item)
    return if (failure != null) {
        val unavailable = failure is FilesFailure.ApiRejected &&
            failure.httpStatusCode == HTTP_NOT_FOUND && failure.statusCode == HTTP_NOT_FOUND
        copy(request = null, state = state.copy(
            restoreOutcome = outcome.copy(
                check = if (unavailable) TrashRestoreCheck.UNAVAILABLE else TrashRestoreCheck.FAILED,
                checkFailure = failure,
            ),
            authenticationFailure = failure.authFailure() ?: state.authenticationFailure,
        ))
    } else {
        val resolved = (result as FilesRepositoryResult.Success).value
        copy(request = null, state = state.copy(
            restoreOutcome = outcome.copy(
                check = TrashRestoreCheck.AVAILABLE, checkFailure = null, resolvedItem = resolved,
            ),
            restoredVersion = state.restoredVersion + 1L,
            restoredItemIds = state.restoredItemIds + resolved.id,
            restoredOccurrences = state.restoredOccurrences + (resolved.id to completed.item.deletedAt),
            lastRestoredItem = resolved,
        )).startList()
    }
}

private fun FilesFailure.isKnownRestoreRejection(): Boolean =
    authFailure() != null || this is FilesFailure.AccessDenied || this is FilesFailure.RateLimited ||
        (this is FilesFailure.ApiRejected && statusCode == HTTP_BAD_REQUEST &&
            httpStatusCode == HTTP_BAD_REQUEST && errorType == "TRASH_INCOMPLETE_TRASH")

private fun FilesRepositoryResult<FilesItem>.readFailure(selected: TrashItem): FilesFailure? = when (this) {
    is FilesRepositoryResult.Failure -> failure
    is FilesRepositoryResult.Success -> {
        val valid = value.id == selected.id && value.parentId != null && value.parentId.value >= 0L &&
            value.type == selected.type
        if (valid) null else FilesFailure.InvalidResponse(invalidTrashResponse(
            "Restored item does not match the selected identity", "/files/${selected.id.value}",
        ))
    }
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404
