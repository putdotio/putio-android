package io.putdotio.android.settings

internal fun AccountSettingsState.retryLoad(): AccountSettingsTransition {
    if (content !is AccountSettingsContent.Failed) {
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
    return if (
        ready == null ||
        mutation != AccountSettingsMutation.Idle ||
        ready.preferences.valueFor(change.key) == change.enabled
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
    if (failed == null || ready == null) {
        return AccountSettingsTransition(this, consumed = false)
    }
    val requestId = nextRequestId()
    return AccountSettingsTransition(
        state =
            copy(
                content = AccountSettingsContent.Ready(ready.preferences.applying(failed.change)),
                mutation =
                    AccountSettingsMutation.Saving(
                        requestId = requestId,
                        change = failed.change,
                        previousPreferences = failed.previousPreferences,
                    ),
                nextRequestValue = requestId.value + 1,
            ),
        effect = AccountSettingsEffect.Save(requestId, failed.change),
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
    return if (saving?.requestId == event.requestId) {
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

internal fun AccountSettingsState.saveFailed(
    event: AccountSettingsEvent.SaveFailed,
): AccountSettingsTransition {
    val saving = mutation as? AccountSettingsMutation.Saving
    return if (saving?.requestId == event.requestId) {
        AccountSettingsTransition(
            copy(
                content = AccountSettingsContent.Ready(saving.previousPreferences),
                mutation =
                    AccountSettingsMutation.Failed(
                        change = saving.change,
                        failure = event.failure,
                        previousPreferences = saving.previousPreferences,
                    ),
            ),
        )
    } else {
        AccountSettingsTransition(this, consumed = false)
    }
}

private fun AccountSettingsState.nextRequestId(): AccountSettingsRequestId =
    AccountSettingsRequestId(nextRequestValue)
