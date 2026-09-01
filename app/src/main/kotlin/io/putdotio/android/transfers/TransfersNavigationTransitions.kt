package io.putdotio.android.transfers

import io.putdotio.android.files.FilesFailure

internal fun TransfersState.open(id: TransferId): TransfersTransition {
    val readInFlight = refresh is TransfersRefresh.Refreshing || content.isPaging
    if (
        mutation is TransferMutation.Running ||
        navigation is TransferNavigation.Resolving ||
        readInFlight
    ) {
        return TransfersTransition(this, consumed = false)
    }
    val item = content.items().firstOrNull { it.id == id }
    val base = if (refresh is TransfersRefresh.Polling) copy(refresh = TransfersRefresh.Idle) else this
    return when {
        item == null ->
            TransfersTransition(this, consumed = false)
        item.canOpen -> {
            val requestId = TransfersRequestId(nextRequestValue)
            TransfersTransition(
                base.copy(
                    navigation = TransferNavigation.Resolving(requireNotNull(item.fileId), requestId),
                    notice = null,
                    nextRequestValue = nextRequestValue + 1,
                ),
            )
        }
        item.userFileExists == false || item.status == AppTransferStatus.Failed ->
            base.notice(TransferNotice.FileUnavailable(id, TransfersRequestId(nextRequestValue)))
        !item.status.isTerminal || item.status == AppTransferStatus.Completed ->
            base.notice(TransferNotice.FilePreparing(id, TransfersRequestId(nextRequestValue)))
        else ->
            base.notice(TransferNotice.FileUnavailable(id, TransfersRequestId(nextRequestValue)))
    }
}

internal fun TransfersState.openSucceeded(requestId: TransfersRequestId): TransfersTransition {
    val resolving = navigation as? TransferNavigation.Resolving
    return if (resolving?.requestId == requestId) {
        TransfersTransition(copy(navigation = TransferNavigation.Idle))
    } else {
        TransfersTransition(this, consumed = false)
    }
}

internal fun TransfersState.openFailed(
    requestId: TransfersRequestId,
    failure: FilesFailure,
): TransfersTransition {
    val resolving = navigation as? TransferNavigation.Resolving
    return if (resolving?.requestId == requestId) {
        TransfersTransition(copy(navigation = TransferNavigation.Failed(failure)))
    } else {
        TransfersTransition(this, consumed = false)
    }
}

internal fun TransfersState.dismissNavigationFailure(): TransfersTransition =
    if (navigation is TransferNavigation.Failed) {
        TransfersTransition(copy(navigation = TransferNavigation.Idle))
    } else {
        TransfersTransition(this, consumed = false)
    }

internal fun TransfersState.dismissNotice(requestId: TransfersRequestId): TransfersTransition =
    if (notice?.requestId == requestId) {
        TransfersTransition(copy(notice = null))
    } else {
        TransfersTransition(this, consumed = false)
    }

private fun TransfersState.notice(notice: TransferNotice): TransfersTransition =
    TransfersTransition(
        copy(
            notice = notice,
            nextRequestValue = nextRequestValue + 1,
        ),
    )
