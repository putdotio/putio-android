package io.putdotio.android.trash

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.sdk.errors.PutioException

internal fun TrashMachine.completeList(
    completed: TrashRequest.ListPage,
    result: FilesRepositoryResult<TrashPage>,
): TrashMachine {
    if (request != completed) return this
    val next = copy(request = null)
    return when (result) {
        is FilesRepositoryResult.Success -> next.acceptPage(completed, result.value)
        is FilesRepositoryResult.Failure -> next.rejectPage(completed, result.failure)
    }
}

private fun TrashMachine.acceptPage(completed: TrashRequest.ListPage, page: TrashPage): TrashMachine {
    val previous = state.content as? TrashContent.Loaded
    val consumed = if (completed.cursor == null) emptySet() else consumedCursors + completed.cursor
    val items = if (completed.cursor == null) page.items else previous?.items.orEmpty() + page.items
    val content = TrashContent.Loaded(
        items = items.distinctBy(TrashItem::id),
        nextCursor = page.nextCursor?.takeUnless { it in consumed },
        total = if (completed.cursor == null) page.total else previous?.total,
        trashSizeBytes = if (completed.cursor == null) page.trashSizeBytes else previous?.trashSizeBytes,
        refreshFailure = if (completed.cursor == null) null else previous?.refreshFailure,
        snapshotCursor = if (completed.cursor == null) page.nextCursor else previous?.snapshotCursor,
    )
    val blockedIds = if (completed.cursor == null) {
        // Only a fresh initial page can establish a new deletion of an already restored ID.
        // Continuations can contain stale rows from the cursor's original snapshot.
        val newOccurrences = content.items.filter { item ->
            val restoredAt = state.restoredOccurrences[item.id]?.let(::parseTrashTimestamp)
            val deletedAt = item.deletedAt?.let(::parseTrashTimestamp)
            !state.hasPendingRestore && restoredAt != null && deletedAt != null && deletedAt > restoredAt
        }.map(TrashItem::id).toSet()
        (state.restoredItemIds + state.restoredOccurrences.keys) - newOccurrences
    } else {
        state.restoredItemIds
    }
    val accepted = copy(state = state.copy(content = content, restoredItemIds = blockedIds), consumedCursors = consumed)
    return if (completed.verifiesAction) accepted.verifyActionAgainstPage(content) else accepted
}

private fun TrashMachine.rejectPage(completed: TrashRequest.ListPage, failure: FilesFailure): TrashMachine {
    val previous = state.content as? TrashContent.Loaded
    val content = when {
        previous == null -> TrashContent.Error(failure)
        completed.cursor == null -> previous.copy(isRefreshing = false, refreshFailure = failure)
        else -> previous.copy(isLoadingMore = false, pageFailure = failure)
    }
    val rejected = copy(state = state.copy(
        content = content,
        confirmation = if (failure.authFailure() != null) null else state.confirmation,
        confirmationId = if (failure.authFailure() != null) null else state.confirmationId,
        actionConfirmation = if (failure.authFailure() != null) null else state.actionConfirmation,
        actionConfirmationId = if (failure.authFailure() != null) null else state.actionConfirmationId,
        authenticationFailure = failure.authFailure() ?: state.authenticationFailure,
    ))
    return if (completed.verifiesAction) rejected.failActionVerification(failure) else rejected
}

internal fun FilesFailure.authFailure(): PutioException? = when (this) {
    is FilesFailure.AuthenticationRequired -> cause
    else -> null
}
