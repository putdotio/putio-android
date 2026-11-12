package io.putdotio.android.trash

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.parsePutioTimestamp

internal fun TrashMachine.completeAction(
    completed: TrashRequest.Act,
    result: PutioResult<Unit>,
): TrashMachine {
    val outcome = state.actionOutcome
    if (request != completed || outcome == null) return this
    val failure = (result as? PutioResult.Failure)?.failure
    val submission = when {
        failure == null -> TrashActionSubmission.ACKNOWLEDGED
        failure.isKnownActionRejection() -> TrashActionSubmission.REJECTED
        else -> TrashActionSubmission.UNCERTAIN
    }
    val acknowledgedBulkRestore = submission == TrashActionSubmission.ACKNOWLEDGED && completed.action.isBulkRestore
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
    val verifiedBulkRestore = check == TrashActionCheck.VERIFIED && outcome.action.isBulkRestore
    return copy(state = state.copy(
        actionOutcome = outcome.copy(check = check, checkFailure = null, failedCount = outcome.failedCount(page)),
        bulkRestoreVersion = state.bulkRestoreVersion + (if (verifiedBulkRestore) 1L else 0L),
    ))
}

private fun TrashActionOutcome.verify(page: TrashContent.Loaded): TrashActionCheck = when (val action = action) {
    is TrashAction.DeleteItem -> verifyGone(page, listOf(action.item), whenListed = TrashActionCheck.FAILED)
    is TrashAction.DeleteSelected -> verifyGone(page, action.items, whenListed = TrashActionCheck.FAILED)
    is TrashAction.RestoreSelected ->
        if (page.passed(action.items)) TrashActionCheck.VERIFIED else TrashActionCheck.INCONCLUSIVE
    TrashAction.Empty -> if (page.isKnownEmpty) TrashActionCheck.VERIFIED else TrashActionCheck.FAILED
    // Bulk restore drains asynchronously. Items deleted after the snapshot were never part of the
    // request, so a complete page holding only those still verifies it.
    TrashAction.RestoreAll -> verifyRestoreAll(page)
}

private fun TrashActionOutcome.verifyGone(
    page: TrashContent.Loaded,
    items: List<TrashItem>,
    whenListed: TrashActionCheck,
): TrashActionCheck {
    val ids = items.mapTo(hashSetOf(), TrashItem::id)
    return when {
        page.items.any { it.id in ids } -> whenListed
        page.nextCursor == null || submission == TrashActionSubmission.ACKNOWLEDGED -> TrashActionCheck.VERIFIED
        else -> TrashActionCheck.INCONCLUSIVE
    }
}

private fun TrashContent.Loaded.passed(rows: List<TrashItem>): Boolean {
    val ids = rows.mapTo(hashSetOf(), TrashItem::id)
    val oldestListed = items.lastOrNull()?.deletedAt?.let(::parsePutioTimestamp)
    val oldestRow = rows.map { it.deletedAt?.let(::parsePutioTimestamp) }
        .takeIf { stamps -> stamps.none { it == null } }?.filterNotNull()?.minOrNull()
    val reachesRows = nextCursor == null ||
        (oldestListed != null && oldestRow != null && oldestListed.isBefore(oldestRow))
    return reachesRows && items.none { it.id in ids }
}

private fun TrashActionOutcome.failedCount(page: TrashContent.Loaded): Int? {
    val items = (action as? TrashAction.DeleteSelected)?.items?.takeIf { page.nextCursor == null }
    val listed = page.items.mapTo(hashSetOf(), TrashItem::id)
    return items?.count { it.id in listed }
}

private val TrashAction.isBulkRestore: Boolean
    get() = this == TrashAction.RestoreAll || this is TrashAction.RestoreSelected

private fun TrashActionOutcome.verifyRestoreAll(page: TrashContent.Loaded): TrashActionCheck {
    val snapshot = restoreSnapshot
    val newest = snapshot?.newestDeletedAt
    return when {
        page.isKnownEmpty -> TrashActionCheck.VERIFIED
        snapshot == null || page.nextCursor != null -> TrashActionCheck.INCONCLUSIVE
        page.items.any { it.id in snapshot.itemIds } -> TrashActionCheck.INCONCLUSIVE
        !snapshot.coversUnloadedItems -> TrashActionCheck.VERIFIED
        newest != null && page.items.all { item ->
            item.deletedAt?.let(::parsePutioTimestamp)?.isAfter(newest) == true
        } -> TrashActionCheck.VERIFIED
        else -> TrashActionCheck.INCONCLUSIVE
    }
}

internal fun TrashMachine.failActionVerification(failure: PutioFailure): TrashMachine {
    val outcome = state.actionOutcome?.takeIf { it.check == TrashActionCheck.CHECKING } ?: return this
    val failed = outcome.copy(check = TrashActionCheck.FAILED, checkFailure = failure)
    return copy(state = state.copy(actionOutcome = failed))
}

private fun PutioFailure.isKnownActionRejection(): Boolean =
    authFailure() != null || this is PutioFailure.AccessDenied || this is PutioFailure.RateLimited ||
        (this is PutioFailure.ApiRejected && httpStatusCode == statusCode &&
            (statusCode == HTTP_BAD_REQUEST || statusCode == HTTP_NOT_FOUND))

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404
