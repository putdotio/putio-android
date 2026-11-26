package io.putdotio.android.settings

import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.AnalyticsEvent.Setting
import io.putdotio.android.telemetry.AnalyticsEvent.SettingValue
import io.putdotio.android.telemetry.ConsentCheck
import io.putdotio.android.telemetry.ProductAnalytics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull

public suspend fun Flow<AccountSettingsState>.captureSavedSettings() {
    captureSaved({ it.mutation.step() }, AccountSettingsChange::analyticsEvent) { state ->
        val ready = state.content as? AccountSettingsContent.Ready
        ready?.let { ProductAnalytics.updateRoute(it.preferences.tunnelRoute.value) }
    }
}

public fun Flow<AccountSettingsState>.productAnalyticsAnswers(): Flow<Boolean> =
    mapNotNull { it.productAnalyticsAllowed() }.distinctUntilChanged()

public suspend fun AccountSettingsRepository.confirmProductAnalytics(check: ConsentCheck, accountId: Long?) {
    val enabled = accountId?.let {
        (load() as? AccountSettingsRepositoryResult.Success)?.value?.productAnalyticsEnabled
    }
    ProductAnalytics.confirm(check, accountId, enabled)
}

public suspend fun Flow<AndroidAppConfigState>.captureSavedAppConfig() {
    captureSaved({ it.mutation.step() }, AndroidAppConfigChange::analyticsEvent)
}

private suspend fun <S, C> Flow<S>.captureSaved(
    step: (S) -> SaveStep<C>,
    event: (C) -> AnalyticsEvent?,
    onState: (S) -> Unit = {},
) {
    var previous: SaveStep<C>? = null
    collect { state ->
        val now = step(state)
        previous?.let(now::savedAfter)?.let(event)?.let(ProductAnalytics::capture)
        onState(state)
        previous = now
    }
}

internal sealed interface SaveStep<out C> {
    data class Saving<C>(val requestId: Any, val change: C, val saving: Boolean) : SaveStep<C>

    data class Failed(val refreshing: Boolean) : SaveStep<Nothing>

    data object Idle : SaveStep<Nothing>
}

internal fun <C> SaveStep<C>.savedAfter(previous: SaveStep<C>): C? {
    val saving = (previous as? SaveStep.Saving)?.takeIf { it.saving } ?: return null
    val saved = when (this) {
        is SaveStep.Saving -> if (requestId == saving.requestId) !this.saving else change != saving.change
        is SaveStep.Failed -> refreshing
        SaveStep.Idle -> true
    }
    return saving.change.takeIf { saved }
}

internal fun AccountSettingsMutation.step(): SaveStep<AccountSettingsChange> =
    when (this) {
        is AccountSettingsMutation.Saving ->
            SaveStep.Saving(requestId, change, operation == AccountSettingsMutation.Operation.Save)
        is AccountSettingsMutation.Failed -> SaveStep.Failed(operation == AccountSettingsMutation.Operation.Refresh)
        AccountSettingsMutation.Idle -> SaveStep.Idle
    }

internal fun AndroidAppConfigMutation.step(): SaveStep<AndroidAppConfigChange> =
    when (this) {
        is AndroidAppConfigMutation.Saving ->
            SaveStep.Saving(requestId, change, operation == AndroidAppConfigMutation.Operation.Save)
        is AndroidAppConfigMutation.Failed -> SaveStep.Failed(operation == AndroidAppConfigMutation.Operation.Refresh)
        AndroidAppConfigMutation.Idle -> SaveStep.Idle
    }

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
            AccountSettingsKey.FolderSorts,
            AccountSettingsKey.Diagnostics,
            AccountSettingsKey.ProductAnalytics,
            -> null
        }
        is AccountSettingsChange.Route -> Setting.TunnelRoute to SettingValue.Changed
        is AccountSettingsChange.Sort -> Setting.SortBy to SettingValue.Changed
        AccountSettingsChange.ResetFolderSorts -> null
    }?.let { (setting, value) -> AnalyticsEvent.SettingsChanged(setting, value) }

internal fun AndroidAppConfigChange.analyticsEvent(): AnalyticsEvent.SettingsChanged? =
    when (this) {
        is AndroidAppConfigChange.PlaybackMethod -> AnalyticsEvent.SettingsChanged(
            Setting.StreamingMethod,
            when (value) {
                StreamingMethod.Hls -> SettingValue.Hls
                StreamingMethod.File -> SettingValue.File
            },
        )
        is AndroidAppConfigChange.AutoplayNextVideo ->
            AnalyticsEvent.SettingsChanged(Setting.AutoplayNextVideo, SettingValue.of(enabled))
        is AndroidAppConfigChange.RotateToLandscape,
        is AndroidAppConfigChange.PutioCastReceiver,
        AndroidAppConfigChange.ProductAnalyticsPromptAnswered,
        -> null
    }
