package io.putdotio.android.settings

internal fun AccountSettingsPreferences.matches(change: AccountSettingsChange): Boolean =
    when (change) {
        is AccountSettingsChange.Toggle -> toggleValue(change.key) == change.enabled
        is AccountSettingsChange.Route -> tunnelRoute == change.name
    }

internal fun AccountSettingsPreferences.applying(change: AccountSettingsChange): AccountSettingsPreferences =
    when (change) {
        is AccountSettingsChange.Toggle -> applyingToggle(change)
        is AccountSettingsChange.Route -> copy(tunnelRoute = change.name)
    }

private fun AccountSettingsPreferences.toggleValue(key: AccountSettingsKey): Boolean =
    when (key) {
        AccountSettingsKey.History -> historyEnabled
        AccountSettingsKey.Trash -> trashEnabled
        AccountSettingsKey.ShowSubtitles -> showSubtitles
        AccountSettingsKey.AutoSelectSubtitles -> autoSelectSubtitles
        AccountSettingsKey.ResumePlayback -> resumePlayback
        AccountSettingsKey.TunnelRoute -> error("Tunnel route is not a toggle")
    }

private fun AccountSettingsPreferences.applyingToggle(
    change: AccountSettingsChange.Toggle,
): AccountSettingsPreferences =
    when (change.key) {
        AccountSettingsKey.History -> copy(historyEnabled = change.enabled)
        AccountSettingsKey.Trash -> copy(trashEnabled = change.enabled)
        AccountSettingsKey.ShowSubtitles -> copy(showSubtitles = change.enabled)
        AccountSettingsKey.AutoSelectSubtitles -> copy(autoSelectSubtitles = change.enabled)
        AccountSettingsKey.ResumePlayback -> copy(resumePlayback = change.enabled)
        AccountSettingsKey.TunnelRoute -> error("Tunnel route is not a toggle")
    }
