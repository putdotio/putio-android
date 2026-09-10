package io.putdotio.android.trash

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesRepositoryResult

internal fun TrashMachine.completeAction(
    completed: TrashRequest.Act,
    result: FilesRepositoryResult<Unit>,
): TrashMachine {
    val outcome = state.actionOutcome
    if (request != completed || outcome == null) return this
    val failure = (result as? FilesRepositoryResult.Failure)?.failure
    val submission = when {
        failure == null -> TrashActionSubmission.ACKNOWLEDGED
        failure.isKnownActionRejection() -> TrashActionSubmission.REJECTED
        else -> TrashActionSubmission.UNCERTAIN
    }
    val acknowledgedBulkRestore =
        submission == TrashActionSubmission.ACKNOWLEDGED && completed.action is TrashAction.RestoreAll
    val next = copy(request = null, state = state.copy(
        actionOutcome = outcome.copy(submission = submission, submissionFailure = failure),
        authenticationFailure = failure?.authFailure() ?: state.authenticationFailure,
        bulkRestoreVersion = state.bulkRestoreVersion + (if (acknowledgedBulkRestore) 1L else 0L),
    ))
    return if (submission == TrashActionSubmission.REJECTED) next else next.startActionCheck() ?: next
}

/** Applied after an initial page that verifies a pending action has been accepted into state. */
internal fun TrashMachine.verifyActionAgainstPage(page: TrashContent.Loaded): TrashMachine {
    val outcome = state.actionOutcome?.takeIf { it.check == TrashActionCheck.CHECKING } ?: return this
    val check = outcome.verify(page)
    val verifiedBulkRestore = check == TrashActionCheck.VERIFIED && outcome.action is TrashAction.RestoreAll
    return copy(state = state.copy(
        actionOutcome = outcome.copy(check = check, checkFailure = null),
        bulkRestoreVersion = state.bulkRestoreVersion + (if (verifiedBulkRestore) 1L else 0L),
    ))
}

private fun TrashActionOutcome.verify(page: TrashContent.Loaded): TrashActionCheck = when (val action = action) {
    is TrashAction.DeleteItem -> when {
        page.items.any { it.id == action.item.id } -> TrashActionCheck.FAILED
        page.nextCursor == null || submission == TrashActionSubmission.ACKNOWLEDGED -> TrashActionCheck.VERIFIED
        else -> TrashActionCheck.INCONCLUSIVE
    }
    TrashAction.Empty -> if (page.isKnownEmpty) TrashActionCheck.VERIFIED else TrashActionCheck.FAILED
    // Bulk restore drains asynchronously. Items deleted after the snapshot were never part of the
    // request, so a complete page holding only those still verifies it.
    TrashAction.RestoreAll -> verifyRestoreAll(page)
}

private fun TrashActionOutcome.verifyRestoreAll(page: TrashContent.Loaded): TrashActionCheck {
    val snapshot = restoreSnapshot
    val newest = snapshot?.newestDeletedAt
    return when {
        page.isKnownEmpty -> TrashActionCheck.VERIFIED
        snapshot == null || page.nextCursor != null -> TrashActionCheck.INCONCLUSIVE
        page.items.any { it.id in snapshot.itemIds } -> TrashActionCheck.INCONCLUSIVE
        !snapshot.coversUnloadedItems -> TrashActionCheck.VERIFIED
        newest != null && page.items.all { item ->
            item.deletedAt?.let(::parseTrashTimestamp)?.isAfter(newest) == true
        } -> TrashActionCheck.VERIFIED
        else -> TrashActionCheck.INCONCLUSIVE
    }
}

internal fun TrashMachine.failActionVerification(failure: FilesFailure): TrashMachine {
    val outcome = state.actionOutcome?.takeIf { it.check == TrashActionCheck.CHECKING } ?: return this
    val failed = outcome.copy(check = TrashActionCheck.FAILED, checkFailure = failure)
    return copy(state = state.copy(actionOutcome = failed))
}

private fun FilesFailure.isKnownActionRejection(): Boolean =
    authFailure() != null || this is FilesFailure.AccessDenied || this is FilesFailure.RateLimited ||
        (this is FilesFailure.ApiRejected && httpStatusCode == statusCode &&
            (statusCode == HTTP_BAD_REQUEST || statusCode == HTTP_NOT_FOUND))

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404
