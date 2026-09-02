package io.putdotio.android

import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigState

internal fun readyAndroidAppConfigState(
    preferences: AndroidAppConfigPreferences = DefaultAndroidAppConfigPreferences,
    mutation: AndroidAppConfigMutation = AndroidAppConfigMutation.Idle,
): AndroidAppConfigState =
    AndroidAppConfigState(
        content = AndroidAppConfigContent.Ready(preferences),
        mutation = mutation,
        nextRequestValue = 2L,
    )

internal val DefaultAndroidAppConfigPreferences = AndroidAppConfigPreferences()
