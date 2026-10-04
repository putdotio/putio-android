package io.putdotio.android.settings

// States as the reducers would hold them, for tests outside this module; request ids continue
// from nextRequestValue.

public fun accountSettingsState(
    content: AccountSettingsContent,
    mutation: AccountSettingsMutation,
    nextRequestValue: Long,
): AccountSettingsState = AccountSettingsState(content, mutation, nextRequestValue)

public fun readyAccountSettingsState(
    preferences: AccountSettingsPreferences = DefaultAccountSettingsPreferences,
    mutation: AccountSettingsMutation = AccountSettingsMutation.Idle,
): AccountSettingsState =
    accountSettingsState(AccountSettingsContent.Ready(preferences), mutation, nextRequestValue = 2L)

public val DefaultAccountSettingsPreferences: AccountSettingsPreferences =
    AccountSettingsPreferences(
        historyEnabled = true,
        trashEnabled = true,
        showSubtitles = true,
        autoSelectSubtitles = true,
    )

public fun androidAppConfigState(
    content: AndroidAppConfigContent,
    mutation: AndroidAppConfigMutation,
    nextRequestValue: Long,
    confirmedPreferences: AndroidAppConfigPreferences? = (content as? AndroidAppConfigContent.Ready)?.preferences,
): AndroidAppConfigState = AndroidAppConfigState(content, mutation, nextRequestValue, confirmedPreferences)

public fun readyAndroidAppConfigState(
    preferences: AndroidAppConfigPreferences = DefaultAndroidAppConfigPreferences,
    mutation: AndroidAppConfigMutation = AndroidAppConfigMutation.Idle,
): AndroidAppConfigState =
    androidAppConfigState(AndroidAppConfigContent.Ready(preferences), mutation, nextRequestValue = 2L)

public val DefaultAndroidAppConfigPreferences: AndroidAppConfigPreferences = AndroidAppConfigPreferences()
