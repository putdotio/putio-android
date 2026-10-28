package io.putdotio.android.settings

import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.AnalyticsEvent.Setting
import io.putdotio.android.telemetry.AnalyticsEvent.SettingValue
import io.putdotio.android.telemetry.ConsentCheck
import io.putdotio.android.telemetry.ProductAnalytics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull

/**
 * Sends `settings_changed` once per save put.io accepts; privacy controls send nothing. Also keeps
 * the route `app_opened` reports.
 */
public suspend fun Flow<AccountSettingsState>.captureSavedSettings() {
    captureSaved({ it.mutation.step() }, AccountSettingsChange::analyticsEvent) { state ->
        val ready = state.content as? AccountSettingsContent.Ready
        ready?.let { ProductAnalytics.updateRoute(it.preferences.tunnelRoute.value) }
    }
}

/**
 * Each new answer these settings give. A save of another setting re-emits the answer they loaded,
 * which a consent check may have replaced since, so an unchanged answer is not repeated.
 */
public fun Flow<AccountSettingsState>.productAnalyticsAnswers(): Flow<Boolean> =
    mapNotNull { it.productAnalyticsAllowed() }.distinctUntilChanged()

/** Answers a [ProductAnalytics.setConsentCheck] for [accountId] from put.io; a failed read keeps the last answer. */
public suspend fun AccountSettingsRepository.confirmProductAnalytics(check: ConsentCheck, accountId: Long?) {
    val enabled = accountId?.let {
        (load() as? AccountSettingsRepositoryResult.Success)?.value?.productAnalyticsEnabled
    }
    ProductAnalytics.confirm(check, accountId, enabled)
}

/** Sends `settings_changed` once per app config save put.io accepts; the prompt's answer sends nothing. */
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

/** One settings controller's mutation, as save detection reads it. */
internal sealed interface SaveStep<out C> {
    data class Saving<C>(val requestId: Any, val change: C, val saving: Boolean) : SaveStep<C>

    data class Failed(val refreshing: Boolean) : SaveStep<Nothing>

    data object Idle : SaveStep<Nothing>
}

/**
 * The change [previous] was saving, once that save left its `Save` step without failing there. A
 * collector may never see the `Refresh` step, so landing anywhere else counts, except the same
 * change saving again: that is a retry of a failure the collector missed.
 */
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

internal fun AndroidAppConfigChange.analyticsEvent(): AnalyticsEvent.SettingsChanged? =
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
        AndroidAppConfigChange.ProductAnalyticsPromptAnswered -> null
    }
