package io.putdotio.android.transfers

/** A Transfers state as the reducer would hold it, for tests outside this module. */
fun transfersState(
    content: TransfersContent,
    refresh: TransfersRefresh = TransfersRefresh.Idle,
    mutation: TransferMutation = TransferMutation.Idle,
    navigation: TransferNavigation = TransferNavigation.Idle,
    notice: TransferNotice? = null,
    retryOutcome: TransferRetryOutcome? = null,
    lastAddReceipt: TransferAddReceipt? = null,
): TransfersState =
    TransfersState(
        content = content,
        refresh = refresh,
        mutation = mutation,
        navigation = navigation,
        notice = notice,
        retryOutcome = retryOutcome,
        lastAddReceipt = lastAddReceipt,
    )

/** [copy] for tests outside this module; request ids and paging keep their place in this state. */
fun TransfersState.copyForTest(
    content: TransfersContent = this.content,
    refresh: TransfersRefresh = this.refresh,
    mutation: TransferMutation = this.mutation,
    navigation: TransferNavigation = this.navigation,
    notice: TransferNotice? = this.notice,
    retryOutcome: TransferRetryOutcome? = this.retryOutcome,
    lastAddReceipt: TransferAddReceipt? = this.lastAddReceipt,
): TransfersState =
    copy(
        content = content,
        refresh = refresh,
        mutation = mutation,
        navigation = navigation,
        notice = notice,
        retryOutcome = retryOutcome,
        lastAddReceipt = lastAddReceipt,
    )
