package io.putdotio.android.settings

internal fun AccountSettingsState.retryLoad(): AccountSettingsTransition {
    val failed = content as? AccountSettingsContent.Failed
    if (
        failed == null ||
        failed.failure is AccountSettingsFailure.AuthenticationRequired
    ) {
        return AccountSettingsTransition(this, consumed = false)
    }
    val requestId = nextRequestId()
    return AccountSettingsTransition(
        state =
            copy(
                content = AccountSettingsContent.Loading(requestId),
                mutation = AccountSettingsMutation.Idle,
                nextRequestValue = requestId.value + 1,
            ),
        effect = AccountSettingsEffect.Load(requestId),
    )
}

internal fun AccountSettingsState.requestChange(change: AccountSettingsChange): AccountSettingsTransition {
    val ready = content as? AccountSettingsContent.Ready
    val mutationBlocksChange =
        when (mutation) {
            AccountSettingsMutation.Idle -> false
            is AccountSettingsMutation.Saving -> true
            is AccountSettingsMutation.Failed ->
                mutation.failure is AccountSettingsFailure.AuthenticationRequired
        }
    return if (
        ready == null ||
        mutationBlocksChange ||
        ready.preferences.matches(change)
    ) {
        AccountSettingsTransition(this, consumed = false)
    } else {
        val requestId = nextRequestId()
        AccountSettingsTransition(
            state =
                copy(
                    content = AccountSettingsContent.Ready(ready.preferences.applying(change)),
                    mutation =
                        AccountSettingsMutation.Saving(
                            requestId = requestId,
                            change = change,
                            previousPreferences = ready.preferences,
                            operation = AccountSettingsMutation.Operation.Save,
                        ),
                    nextRequestValue = requestId.value + 1,
                ),
            effect = AccountSettingsEffect.Save(requestId, change),
        )
    }
}

internal fun AccountSettingsState.retryChange(): AccountSettingsTransition {
    val failed = mutation as? AccountSettingsMutation.Failed
    val ready = content as? AccountSettingsContent.Ready
    if (
        failed == null ||
        ready == null ||
        failed.failure is AccountSettingsFailure.AuthenticationRequired
    ) {
        return AccountSettingsTransition(this, consumed = false)
    }
    val requestId = nextRequestId()
    val effect =
        when (failed.operation) {
            AccountSettingsMutation.Operation.Save -> AccountSettingsEffect.Save(requestId, failed.change)
            AccountSettingsMutation.Operation.Refresh -> AccountSettingsEffect.Refresh(requestId)
        }
    return AccountSettingsTransition(
        state =
            copy(
                content =
                    AccountSettingsContent.Ready(
                        if (failed.operation == AccountSettingsMutation.Operation.Save) {
                            ready.preferences.applying(failed.change)
                        } else {
                            ready.preferences
                        },
                    ),
                mutation =
                    AccountSettingsMutation.Saving(
                        requestId = requestId,
                        change = failed.change,
                        previousPreferences = failed.previousPreferences,
                        operation = failed.operation,
                    ),
                nextRequestValue = requestId.value + 1,
            ),
        effect = effect,
    )
}

internal fun AccountSettingsState.loadSucceeded(
    event: AccountSettingsEvent.LoadSucceeded,
): AccountSettingsTransition {
    val loading = content as? AccountSettingsContent.Loading
    return if (loading?.requestId == event.requestId) {
        AccountSettingsTransition(copy(content = AccountSettingsContent.Ready(event.preferences)))
    } else {
        AccountSettingsTransition(this, consumed = false)
    }
}

internal fun AccountSettingsState.loadFailed(
    event: AccountSettingsEvent.LoadFailed,
): AccountSettingsTransition {
    val loading = content as? AccountSettingsContent.Loading
    return if (loading?.requestId == event.requestId) {
        AccountSettingsTransition(copy(content = AccountSettingsContent.Failed(event.failure)))
    } else {
        AccountSettingsTransition(this, consumed = false)
    }
}

internal fun AccountSettingsState.saveSucceeded(
    event: AccountSettingsEvent.SaveSucceeded,
): AccountSettingsTransition {
    val saving = mutation as? AccountSettingsMutation.Saving
    return if (
        saving?.requestId == event.requestId &&
        saving.operation == AccountSettingsMutation.Operation.Save
    ) {
        AccountSettingsTransition(
            state =
                copy(
                    mutation = saving.copy(operation = AccountSettingsMutation.Operation.Refresh),
                ),
            effect = AccountSettingsEffect.Refresh(event.requestId),
        )
    } else {
        AccountSettingsTransition(this, consumed = false)
    }
}

internal fun AccountSettingsState.saveFailed(
    event: AccountSettingsEvent.SaveFailed,
): AccountSettingsTransition {
    val saving = mutation as? AccountSettingsMutation.Saving
    return if (
        saving?.requestId == event.requestId &&
        saving.operation == AccountSettingsMutation.Operation.Save
    ) {
        AccountSettingsTransition(
            copy(
                content = AccountSettingsContent.Ready(saving.previousPreferences),
                mutation =
                    AccountSettingsMutation.Failed(
                        change = saving.change,
                        failure = event.failure,
                        previousPreferences = saving.previousPreferences,
                        operation = AccountSettingsMutation.Operation.Save,
                    ),
            ),
        )
    } else {
        AccountSettingsTransition(this, consumed = false)
    }
}

internal fun AccountSettingsState.refreshSucceeded(
    event: AccountSettingsEvent.RefreshSucceeded,
): AccountSettingsTransition {
    val saving = mutation as? AccountSettingsMutation.Saving
    return if (
        saving?.requestId == event.requestId &&
        saving.operation == AccountSettingsMutation.Operation.Refresh
    ) {
        AccountSettingsTransition(
            copy(
                content = AccountSettingsContent.Ready(event.preferences),
                mutation = AccountSettingsMutation.Idle,
            ),
        )
    } else {
        AccountSettingsTransition(this, consumed = false)
    }
}

internal fun AccountSettingsState.refreshFailed(
    event: AccountSettingsEvent.RefreshFailed,
): AccountSettingsTransition {
    val saving = mutation as? AccountSettingsMutation.Saving
    return if (
        saving?.requestId == event.requestId &&
        saving.operation == AccountSettingsMutation.Operation.Refresh
    ) {
        AccountSettingsTransition(
            copy(
                mutation =
                    AccountSettingsMutation.Failed(
                        change = saving.change,
                        failure = event.failure,
                        previousPreferences = saving.previousPreferences,
                        operation = AccountSettingsMutation.Operation.Refresh,
                    ),
            ),
        )
    } else {
        AccountSettingsTransition(this, consumed = false)
    }
}

private fun AccountSettingsState.nextRequestId(): AccountSettingsRequestId =
    AccountSettingsRequestId(nextRequestValue)
