package io.putdotio.android.settings

import io.putdotio.android.PutioFailure

public sealed interface HistoryOpen {
    public data object Pending : HistoryOpen

    public data object History : HistoryOpen

    public data object FilesRoot : HistoryOpen

    public data class Failed(
        val failure: PutioFailure,
    ) : HistoryOpen
}

public fun AccountSettingsState.historyOpen(): HistoryOpen {
    val lastRead = when (val write = mutation) {
        AccountSettingsMutation.Idle -> null
        is AccountSettingsMutation.Saving -> write.previousPreferences
        is AccountSettingsMutation.Failed -> write.previousPreferences
    }?.takeIf { mutation.pending?.first == AccountSettingsKey.History }
    val enabled = lastRead?.historyEnabled ?: confirmedHistoryEnabled()
    val loadFailure = (content as? AccountSettingsContent.Failed)?.failure?.putioFailure
    return when {
        enabled == true -> HistoryOpen.History
        enabled == false -> HistoryOpen.FilesRoot
        loadFailure != null -> HistoryOpen.Failed(loadFailure)
        else -> HistoryOpen.Pending
    }
}

public fun AccountSettingsState.historyTabShown(): Boolean = historyOpen() != HistoryOpen.FilesRoot
