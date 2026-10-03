package io.putdotio.android

import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsState

internal fun readyAccountSettingsState(
    preferences: AccountSettingsPreferences = DefaultAccountSettingsPreferences,
    mutation: AccountSettingsMutation = AccountSettingsMutation.Idle,
): AccountSettingsState =
    AccountSettingsState(
        content = AccountSettingsContent.Ready(preferences),
        mutation = mutation,
        nextRequestValue = 2L,
    )

internal val DefaultAccountSettingsPreferences =
    AccountSettingsPreferences(
        historyEnabled = true,
        trashEnabled = true,
        showSubtitles = true,
        autoSelectSubtitles = true,
    )
