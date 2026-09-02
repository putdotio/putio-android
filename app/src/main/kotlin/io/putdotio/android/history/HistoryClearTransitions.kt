package io.putdotio.android.history

internal fun HistoryState.requestClear(): HistoryTransition =
    if (content is HistoryContent.Ready && clearing == HistoryClearing.Idle) {
        HistoryTransition(copy(clearing = HistoryClearing.AwaitingConfirmation))
    } else {
        HistoryTransition(this, consumed = false)
    }

internal fun HistoryState.dismissClear(): HistoryTransition =
    if (clearing == HistoryClearing.AwaitingConfirmation || clearing is HistoryClearing.Failed) {
        HistoryTransition(copy(clearing = HistoryClearing.Idle))
    } else {
        HistoryTransition(this, consumed = false)
    }

internal fun HistoryState.confirmClear(): HistoryTransition {
    val requestId = HistoryRequestId(nextRequestValue)
    return if (clearing == HistoryClearing.AwaitingConfirmation) {
        HistoryTransition(
            copy(clearing = HistoryClearing.Clearing(requestId), nextRequestValue = nextRequestValue + 1),
            HistoryEffect.Clear(requestId),
        )
    } else {
        HistoryTransition(this, consumed = false)
    }
}

internal fun HistoryState.clearSucceeded(event: HistoryEvent.ClearSucceeded): HistoryTransition {
    val active = clearing as? HistoryClearing.Clearing
    return if (active?.requestId == event.requestId) {
        HistoryTransition(
            copy(
                content = if (content is HistoryContent.Disabled) content else HistoryContent.Empty,
                clearing = HistoryClearing.Idle,
                consumedBefore = emptySet(),
            ),
        )
    } else {
        HistoryTransition(this, consumed = false)
    }
}

internal fun HistoryState.clearFailed(event: HistoryEvent.ClearFailed): HistoryTransition {
    (event.failure as? io.putdotio.android.files.FilesFailure.AuthenticationRequired)?.let {
        return HistoryTransition(copy(authoritativeFailure = it))
    }
    val active = clearing as? HistoryClearing.Clearing
    return if (active?.requestId == event.requestId) {
        HistoryTransition(copy(clearing = HistoryClearing.Failed(event.failure)))
    } else {
        HistoryTransition(this, consumed = false)
    }
}
