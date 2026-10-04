package io.putdotio.android.trash

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItem

internal fun TrashMachine.completeRestore(
    completed: TrashRequest.Restore,
    result: PutioResult<Unit>,
): TrashMachine {
    val outcome = state.restoreOutcome
    if (request != completed || outcome == null) return this
    val failure = (result as? PutioResult.Failure)?.failure
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
    result: PutioResult<FilesItem>,
): TrashMachine {
    val outcome = state.restoreOutcome
    if (request != completed || outcome == null) return this
    val failure = result.readFailure(completed.item)
    return if (failure != null) {
        val unavailable = failure is PutioFailure.ApiRejected &&
            failure.httpStatusCode == HTTP_NOT_FOUND && failure.statusCode == HTTP_NOT_FOUND
        copy(request = null, state = state.copy(
            restoreOutcome = outcome.copy(
                check = if (unavailable) TrashRestoreCheck.UNAVAILABLE else TrashRestoreCheck.FAILED,
                checkFailure = failure,
            ),
            authenticationFailure = failure.authFailure() ?: state.authenticationFailure,
        ))
    } else {
        val resolved = (result as PutioResult.Success).value
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

private fun PutioFailure.isKnownRestoreRejection(): Boolean =
    authFailure() != null || this is PutioFailure.AccessDenied || this is PutioFailure.RateLimited ||
        (this is PutioFailure.ApiRejected && statusCode == HTTP_BAD_REQUEST &&
            httpStatusCode == HTTP_BAD_REQUEST && errorType == "TRASH_INCOMPLETE_TRASH")

private fun PutioResult<FilesItem>.readFailure(selected: TrashItem): PutioFailure? = when (this) {
    is PutioResult.Failure -> failure
    is PutioResult.Success -> {
        val parentId = value.parentId
        val valid = value.id == selected.id && parentId != null && parentId.value >= 0L &&
            value.type == selected.type
        if (valid) null else PutioFailure.InvalidResponse(invalidTrashResponse(
            "Restored item does not match the selected identity", "/files/${selected.id.value}",
        ))
    }
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404
