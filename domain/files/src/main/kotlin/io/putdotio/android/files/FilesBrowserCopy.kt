package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import kotlinx.coroutines.delay

internal fun FilesBrowserState.reduceCopy(event: FilesBrowserEvent.CopyEvent): FilesBrowserTransition = when (event) {
    is FilesBrowserEvent.Copy -> startCopy(event)
    is FilesBrowserEvent.CopyStarted -> copyStarted(event)
    is FilesBrowserEvent.CopyChecked -> copyChecked(event)
    FilesBrowserEvent.DismissCopyOutcome -> if (copyOutcome != null && !copyOutcome.isRunning) {
        FilesBrowserTransition(copy(copyOutcome = null))
    } else {
        FilesBrowserTransition(this, consumed = false)
    }
}

private fun FilesBrowserState.startCopy(event: FilesBrowserEvent.Copy): FilesBrowserTransition {
    val item = current.content.items().firstOrNull { it.id == event.itemId && it.canMakeCopy }
        ?.takeIf { event.folderId == current.folder.id && event.destination.id.value >= 0L && canStartCopy }
        ?: return FilesBrowserTransition(this, consumed = false)
    val requestId = FilesRequestId(nextRequestValue)
    val outcome = FilesCopyOutcome(item.name, event.destination, FilesCopyStatus.STARTING, requestId = requestId)
    return FilesBrowserTransition(
        copy(copyOutcome = outcome, nextRequestValue = nextRequestValue + 1),
        FilesBrowserEffect.StartCopy(item.id, event.destination.id, requestId),
    )
}

private fun FilesBrowserState.copyStarted(event: FilesBrowserEvent.CopyStarted): FilesBrowserTransition {
    val outcome = copyOutcome?.takeIf { it.status == FilesCopyStatus.STARTING && it.requestId == event.requestId }
        ?: return FilesBrowserTransition(this, consumed = false)
    return when (val result = event.result) {
        is PutioResult.Success ->
            checkAgain(outcome.copy(status = FilesCopyStatus.COPYING, copyId = result.value))
        is PutioResult.Failure -> {
            val status = if (result.failure.mayHaveStartedCopy) FilesCopyStatus.UNCONFIRMED else FilesCopyStatus.FAILED
            settle(outcome.copy(status = status, failure = result.failure, requestId = null))
        }
    }
}

/** A lost or unreadable answer, or a server fault, leaves open whether put.io accepted the copy. */
private val PutioFailure.mayHaveStartedCopy: Boolean
    get() = this is PutioFailure.NetworkUnavailable || this is PutioFailure.InvalidResponse ||
        this is PutioFailure.ServerUnavailable || this is PutioFailure.Unexpected

private fun FilesBrowserState.copyChecked(event: FilesBrowserEvent.CopyChecked): FilesBrowserTransition {
    val outcome = copyOutcome?.takeIf { it.status == FilesCopyStatus.COPYING && it.requestId == event.requestId }
        ?: return FilesBrowserTransition(this, consumed = false)
    val settled = outcome.copy(requestId = null)
    return when (val result = event.result) {
        is PutioResult.Failure ->
            settle(settled.copy(status = FilesCopyStatus.UNCONFIRMED, failure = result.failure))
        is PutioResult.Success -> when (val progress = result.value) {
            FilesCopyProgress.Done -> settle(settled.copy(status = FilesCopyStatus.COPIED))
            is FilesCopyProgress.Failed ->
                settle(settled.copy(status = FilesCopyStatus.FAILED, serverMessage = progress.message))
            FilesCopyProgress.Running -> if (outcome.checks >= MAX_COPY_CHECKS) {
                settle(settled.copy(status = FilesCopyStatus.UNCONFIRMED))
            } else {
                checkAgain(outcome)
            }
        }
    }
}

private fun FilesBrowserState.checkAgain(outcome: FilesCopyOutcome): FilesBrowserTransition {
    val copyId = outcome.copyId ?: return FilesBrowserTransition(this, consumed = false)
    val requestId = FilesRequestId(nextRequestValue)
    return FilesBrowserTransition(
        copy(
            copyOutcome = outcome.copy(requestId = requestId, checks = outcome.checks + 1),
            nextRequestValue = nextRequestValue + 1,
        ),
        FilesBrowserEffect.CheckCopy(copyId, requestId),
    )
}

// A folder opened later reads fresh, so only a destination already in the stack can show a stale listing.
private fun FilesBrowserState.settle(outcome: FilesCopyOutcome): FilesBrowserTransition {
    val mayHaveLanded = outcome.status == FilesCopyStatus.COPIED || outcome.status == FilesCopyStatus.UNCONFIRMED
    val stack = if (mayHaveLanded) {
        stack.map { if (it.folder.id == outcome.destination.id) it.copy(needsReload = true) else it }
    } else {
        stack
    }
    return FilesBrowserTransition(copy(stack = stack, copyOutcome = outcome))
}

internal suspend fun FilesRepository.executeCopy(effect: FilesBrowserEffect.CopyEffect): FilesBrowserEvent =
    when (effect) {
        is FilesBrowserEffect.StartCopy ->
            FilesBrowserEvent.CopyStarted(effect.requestId, startCopy(effect.itemId, effect.destinationId))
        is FilesBrowserEffect.CheckCopy -> {
            delay(COPY_CHECK_INTERVAL_MILLIS)
            FilesBrowserEvent.CopyChecked(effect.requestId, checkCopy(effect.copyId))
        }
    }

internal fun FilesCopyOutcome?.hasRequest(requestId: FilesRequestId): Boolean = this?.requestId == requestId

/** Web checks a running copy every 1.5 s; Android stops asking after about five minutes. */
internal const val COPY_CHECK_INTERVAL_MILLIS = 1_500L
internal const val MAX_COPY_CHECKS = 200
