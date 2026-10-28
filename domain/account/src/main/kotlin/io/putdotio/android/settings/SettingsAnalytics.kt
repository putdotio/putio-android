package io.putdotio.android.settings

import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.AnalyticsEvent.Setting
import io.putdotio.android.telemetry.AnalyticsEvent.SettingValue
import io.putdotio.android.telemetry.ProductAnalytics
import kotlinx.coroutines.flow.Flow

/** Sends `settings_changed` once per save put.io accepts; privacy controls send nothing. */
public suspend fun Flow<AccountSettingsState>.captureSavedSettings() {
    var previous: AccountSettingsState? = null
    collect { state ->
        previous?.let(state::savedChange)?.analyticsEvent()?.let(ProductAnalytics::capture)
        previous = state
    }
}

/** Sends `settings_changed` once per app config save put.io accepts. */
public suspend fun Flow<AndroidAppConfigState>.captureSavedAppConfig() {
    var previous: AndroidAppConfigState? = null
    collect { state ->
        previous?.let(state::savedChange)?.analyticsEvent()?.let(ProductAnalytics::capture)
        previous = state
    }
}

/**
 * The change [previous] was saving, once that save left its `Save` step without failing there.
 * A collector may never see the `Refresh` step, so landing anywhere else counts.
 */
internal fun AccountSettingsState.savedChange(previous: AccountSettingsState): AccountSettingsChange? {
    val refresh = AccountSettingsMutation.Operation.Refresh
    val saving = (previous.mutation as? AccountSettingsMutation.Saving)
        ?.takeIf { it.operation == AccountSettingsMutation.Operation.Save }
    val saved = when (val now = mutation) {
        is AccountSettingsMutation.Saving -> now.requestId != saving?.requestId || now.operation == refresh
        is AccountSettingsMutation.Failed -> now.operation == refresh
        AccountSettingsMutation.Idle -> true
    }
    return saving?.change?.takeIf { saved }
}

internal fun AndroidAppConfigState.savedChange(previous: AndroidAppConfigState): AndroidAppConfigChange? {
    val refresh = AndroidAppConfigMutation.Operation.Refresh
    val saving = (previous.mutation as? AndroidAppConfigMutation.Saving)
        ?.takeIf { it.operation == AndroidAppConfigMutation.Operation.Save }
    val saved = when (val now = mutation) {
        is AndroidAppConfigMutation.Saving -> now.requestId != saving?.requestId || now.operation == refresh
        is AndroidAppConfigMutation.Failed -> now.operation == refresh
        AndroidAppConfigMutation.Idle -> true
    }
    return saving?.change?.takeIf { saved }
}

/** A sort or route is `changed`, so its value never leaves the device. */
internal fun AccountSettingsChange.analyticsEvent(): AnalyticsEvent.SettingsChanged? =
    when (this) {
        is AccountSettingsChange.Toggle -> when (key) {
            AccountSettingsKey.History -> Setting.HistoryEnabled to SettingValue.of(enabled)
            AccountSettingsKey.Trash -> Setting.TrashEnabled to SettingValue.of(enabled)
            AccountSettingsKey.ShowSubtitles -> Setting.HideSubtitles to SettingValue.of(!enabled)
            AccountSettingsKey.AutoSelectSubtitles -> Setting.DontAutoSelectSubtitles to SettingValue.of(!enabled)
            AccountSettingsKey.ResumePlayback -> Setting.UseStartFrom to SettingValue.of(enabled)
            AccountSettingsKey.TunnelRoute,
            AccountSettingsKey.DefaultSort,
            AccountSettingsKey.Diagnostics,
            AccountSettingsKey.ProductAnalytics,
            -> null
        }
        is AccountSettingsChange.Route -> Setting.TunnelRoute to SettingValue.Changed
        is AccountSettingsChange.Sort -> Setting.SortBy to SettingValue.Changed
    }?.let { (setting, value) -> AnalyticsEvent.SettingsChanged(setting, value) }

internal fun AndroidAppConfigChange.analyticsEvent(): AnalyticsEvent.SettingsChanged =
    when (this) {
        is AndroidAppConfigChange.VideoPlayback -> AnalyticsEvent.SettingsChanged(
            Setting.VideoPlaybackType,
            when (value) {
                VideoPlaybackType.Hls -> SettingValue.Hls
                VideoPlaybackType.Mp4 -> SettingValue.Mp4
            },
        )
        is AndroidAppConfigChange.AutoplayNextVideo ->
            AnalyticsEvent.SettingsChanged(Setting.AutoplayNextVideo, SettingValue.of(enabled))
    }
