package io.putdotio.android.settings

internal fun AndroidAppConfigState.retryLoad(): AndroidAppConfigTransition {
    val failed = content as? AndroidAppConfigContent.Failed
    if (
        failed == null ||
        failed.failure is AndroidAppConfigFailure.AuthenticationRequired
    ) {
        return AndroidAppConfigTransition(this, consumed = false)
    }
    val requestId = nextRequestId()
    return AndroidAppConfigTransition(
        state =
            copy(
                content = AndroidAppConfigContent.Loading(requestId),
                mutation = AndroidAppConfigMutation.Idle,
                nextRequestValue = requestId.value + 1,
            ),
        effect = AndroidAppConfigEffect.Load(requestId),
    )
}

internal fun AndroidAppConfigState.requestChange(change: AndroidAppConfigChange): AndroidAppConfigTransition {
    val ready = content as? AndroidAppConfigContent.Ready
    val mutationBlocksChange =
        when (mutation) {
            AndroidAppConfigMutation.Idle -> false
            is AndroidAppConfigMutation.Saving -> true
            is AndroidAppConfigMutation.Failed ->
                mutation.failure is AndroidAppConfigFailure.AuthenticationRequired
        }
    if (
        ready == null ||
        mutationBlocksChange ||
        ready.preferences.applying(change) == ready.preferences
    ) {
        return AndroidAppConfigTransition(this, consumed = false)
    }

    val requestId = nextRequestId()
    return AndroidAppConfigTransition(
        state =
            copy(
                content = AndroidAppConfigContent.Ready(ready.preferences.applying(change)),
                mutation =
                    AndroidAppConfigMutation.Saving(
                        requestId = requestId,
                        change = change,
                        previousPreferences = ready.preferences,
                        operation = AndroidAppConfigMutation.Operation.Save,
                    ),
                nextRequestValue = requestId.value + 1,
            ),
        effect = AndroidAppConfigEffect.Save(requestId, change),
    )
}

internal fun AndroidAppConfigState.retryChange(): AndroidAppConfigTransition {
    val failed = mutation as? AndroidAppConfigMutation.Failed
    val ready = content as? AndroidAppConfigContent.Ready
    if (
        failed == null ||
        ready == null ||
        failed.failure is AndroidAppConfigFailure.AuthenticationRequired
    ) {
        return AndroidAppConfigTransition(this, consumed = false)
    }

    val requestId = nextRequestId()
    val effect =
        when (failed.operation) {
            AndroidAppConfigMutation.Operation.Save -> AndroidAppConfigEffect.Save(requestId, failed.change)
            AndroidAppConfigMutation.Operation.Refresh -> AndroidAppConfigEffect.Refresh(requestId)
        }
    return AndroidAppConfigTransition(
        state =
            copy(
                content =
                    AndroidAppConfigContent.Ready(
                        if (failed.operation == AndroidAppConfigMutation.Operation.Save) {
                            ready.preferences.applying(failed.change)
                        } else {
                            ready.preferences
                        },
                    ),
                mutation =
                    AndroidAppConfigMutation.Saving(
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

internal fun AndroidAppConfigState.loadSucceeded(
    event: AndroidAppConfigEvent.LoadSucceeded,
): AndroidAppConfigTransition {
    val loading = content as? AndroidAppConfigContent.Loading
    return if (loading?.requestId == event.requestId) {
        AndroidAppConfigTransition(
            copy(
                content = AndroidAppConfigContent.Ready(event.preferences),
                confirmedPreferences = event.preferences,
            ),
        )
    } else {
        AndroidAppConfigTransition(this, consumed = false)
    }
}

internal fun AndroidAppConfigState.loadFailed(
    event: AndroidAppConfigEvent.LoadFailed,
): AndroidAppConfigTransition {
    val loading = content as? AndroidAppConfigContent.Loading
    return if (loading?.requestId == event.requestId) {
        AndroidAppConfigTransition(copy(content = AndroidAppConfigContent.Failed(event.failure)))
    } else {
        AndroidAppConfigTransition(this, consumed = false)
    }
}

internal fun AndroidAppConfigState.saveSucceeded(
    event: AndroidAppConfigEvent.SaveSucceeded,
): AndroidAppConfigTransition {
    val saving = mutation as? AndroidAppConfigMutation.Saving
    return if (
        saving?.requestId == event.requestId &&
        saving.operation == AndroidAppConfigMutation.Operation.Save
    ) {
        AndroidAppConfigTransition(
            state = copy(mutation = saving.copy(operation = AndroidAppConfigMutation.Operation.Refresh)),
            effect = AndroidAppConfigEffect.Refresh(event.requestId),
        )
    } else {
        AndroidAppConfigTransition(this, consumed = false)
    }
}

internal fun AndroidAppConfigState.saveFailed(
    event: AndroidAppConfigEvent.SaveFailed,
): AndroidAppConfigTransition {
    val saving = mutation as? AndroidAppConfigMutation.Saving
    return if (
        saving?.requestId == event.requestId &&
        saving.operation == AndroidAppConfigMutation.Operation.Save
    ) {
        AndroidAppConfigTransition(
            copy(
                content = AndroidAppConfigContent.Ready(saving.previousPreferences),
                mutation =
                    AndroidAppConfigMutation.Failed(
                        change = saving.change,
                        failure = event.failure,
                        previousPreferences = saving.previousPreferences,
                        operation = AndroidAppConfigMutation.Operation.Save,
                    ),
            ),
        )
    } else {
        AndroidAppConfigTransition(this, consumed = false)
    }
}

internal fun AndroidAppConfigState.refreshSucceeded(
    event: AndroidAppConfigEvent.RefreshSucceeded,
): AndroidAppConfigTransition {
    val saving = mutation as? AndroidAppConfigMutation.Saving
    return if (
        saving?.requestId == event.requestId &&
        saving.operation == AndroidAppConfigMutation.Operation.Refresh
    ) {
        AndroidAppConfigTransition(
            copy(
                content = AndroidAppConfigContent.Ready(event.preferences),
                mutation = AndroidAppConfigMutation.Idle,
                confirmedPreferences = event.preferences,
            ),
        )
    } else {
        AndroidAppConfigTransition(this, consumed = false)
    }
}

internal fun AndroidAppConfigState.refreshFailed(
    event: AndroidAppConfigEvent.RefreshFailed,
): AndroidAppConfigTransition {
    val saving = mutation as? AndroidAppConfigMutation.Saving
    return if (
        saving?.requestId == event.requestId &&
        saving.operation == AndroidAppConfigMutation.Operation.Refresh
    ) {
        AndroidAppConfigTransition(
            copy(
                mutation =
                    AndroidAppConfigMutation.Failed(
                        change = saving.change,
                        failure = event.failure,
                        previousPreferences = saving.previousPreferences,
                        operation = AndroidAppConfigMutation.Operation.Refresh,
                    ),
            ),
        )
    } else {
        AndroidAppConfigTransition(this, consumed = false)
    }
}

private fun AndroidAppConfigState.nextRequestId(): AndroidAppConfigRequestId =
    AndroidAppConfigRequestId(nextRequestValue)
