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
                    mutation = AccountSettingsMutation.Saving(requestId, change),
                    nextRequestValue = requestId.value + 1,
                ),
            effect = AccountSettingsEffect.Save(requestId, change),
        )
    }
}

internal fun AccountSettingsState.retryChange(): AccountSettingsTransition {
    val failed = mutation as? AccountSettingsMutation.Failed
        ?: return AccountSettingsTransition(this, consumed = false)
    val requestId = nextRequestId()
    return AccountSettingsTransition(
        state =
            copy(
                mutation = AccountSettingsMutation.Saving(requestId, failed.change),
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
            copy(mutation = AccountSettingsMutation.Failed(saving.change, event.failure)),
        )
    } else {
        AccountSettingsTransition(this, consumed = false)
    }
}

private fun AccountSettingsState.nextRequestId(): AccountSettingsRequestId =
    AccountSettingsRequestId(nextRequestValue)
