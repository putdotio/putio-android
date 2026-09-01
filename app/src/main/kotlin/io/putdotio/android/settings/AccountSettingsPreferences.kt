package io.putdotio.android.settings

internal fun AccountSettingsPreferences.valueFor(key: AccountSettingsKey): Boolean =
    when (key) {
        AccountSettingsKey.History -> historyEnabled
        AccountSettingsKey.Trash -> trashEnabled
        AccountSettingsKey.ShowSubtitles -> showSubtitles
        AccountSettingsKey.AutoSelectSubtitles -> autoSelectSubtitles
    }

internal fun AccountSettingsPreferences.applying(change: AccountSettingsChange): AccountSettingsPreferences =
    when (change.key) {
        AccountSettingsKey.History -> copy(historyEnabled = change.enabled)
        AccountSettingsKey.Trash -> copy(trashEnabled = change.enabled)
        AccountSettingsKey.ShowSubtitles -> copy(showSubtitles = change.enabled)
        AccountSettingsKey.AutoSelectSubtitles -> copy(autoSelectSubtitles = change.enabled)
    }
