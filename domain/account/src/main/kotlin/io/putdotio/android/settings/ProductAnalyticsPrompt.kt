package io.putdotio.android.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Whether the signed-in shell asks about product analytics, or null until both reads land. Unset
 * reads as off, so an account is asked until it turns analytics on or this app stores an answer.
 */
public fun productAnalyticsPromptDue(
    settings: AccountSettingsState,
    appConfig: AndroidAppConfigState,
): Boolean? {
    val account = (settings.content as? AccountSettingsContent.Ready)?.preferences
    val config = (appConfig.content as? AndroidAppConfigContent.Ready)?.preferences
    return if (account == null || config == null) {
        null
    } else {
        !account.productAnalyticsEnabled && !config.productAnalyticsPromptAnswered
    }
}

/**
 * Whether a pending answer can be written now: false while the config is unloaded or busy with another
 * write, null once it is recorded or its write failed. A failed write isn't retried; the next launch
 * asks again.
 */
public fun AndroidAppConfigState.canRecordProductAnalyticsAnswer(): Boolean? {
    val config = (content as? AndroidAppConfigContent.Ready)?.preferences
    return when {
        config?.productAnalyticsPromptAnswered == true || productAnalyticsAnswerFailed() -> null
        config == null -> false
        else -> mutation !is AndroidAppConfigMutation.Saving
    }
}

/**
 * Each product analytics choice put.io accepts, from the prompt or Privacy controls. The account must
 * hold the choice too: a later save can hide a failure the collector missed.
 */
public fun Flow<AccountSettingsState>.acceptedProductAnalyticsChoices(): Flow<Boolean> =
    flow {
        var previous: SaveStep<AccountSettingsChange>? = null
        collect { state ->
            val now = state.mutation.step()
            val change = previous?.let(now::savedAfter) as? AccountSettingsChange.Toggle
            val held = (state.content as? AccountSettingsContent.Ready)?.preferences?.productAnalyticsEnabled
            if (change?.key == AccountSettingsKey.ProductAnalytics && held == change.enabled) emit(change.enabled)
            previous = now
        }
    }

/** The prompt's next write: the setting, then, once put.io holds it, the answer. */
public sealed interface ProductAnalyticsAnswerStep {
    public data class SaveSetting(
        val event: AccountSettingsEvent,
    ) : ProductAnalyticsAnswerStep

    public data class MarkAnswered(
        val event: AndroidAppConfigEvent,
    ) : ProductAnalyticsAnswerStep

    public data object Wait : ProductAnalyticsAnswerStep
}

public fun productAnalyticsAnswerStep(
    enabled: Boolean,
    settings: AccountSettingsState,
    appConfig: AndroidAppConfigState,
): ProductAnalyticsAnswerStep {
    val account = (settings.content as? AccountSettingsContent.Ready)?.preferences
    val config = (appConfig.content as? AndroidAppConfigContent.Ready)?.preferences
    // The refresh after a save doesn't hold the answer back: put.io already took the setting.
    val settingBusy = (settings.mutation as? AccountSettingsMutation.Saving)
        ?.operation == AccountSettingsMutation.Operation.Save
    return when {
        account == null || config == null || settingBusy ->
            ProductAnalyticsAnswerStep.Wait
        account.productAnalyticsEnabled != enabled ->
            ProductAnalyticsAnswerStep.SaveSetting(
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.ProductAnalytics, enabled),
                ),
            )
        config.productAnalyticsPromptAnswered || appConfig.mutation is AndroidAppConfigMutation.Saving ->
            ProductAnalyticsAnswerStep.Wait
        else ->
            ProductAnalyticsAnswerStep.MarkAnswered(
                AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.ProductAnalyticsPromptAnswered),
            )
    }
}

/** Where the prompt's answer stands once the viewer chose [enabled]. */
public sealed interface ProductAnalyticsAnswerProgress {
    /** Nothing in flight, for example after the process died mid-save: the viewer can answer again. */
    public data object Idle : ProductAnalyticsAnswerProgress

    public data object Saving : ProductAnalyticsAnswerProgress

    public data class Failed(
        val failure: AccountSettingsFailure,
    ) : ProductAnalyticsAnswerProgress

    public data object Answered : ProductAnalyticsAnswerProgress
}

/**
 * [staleAnswerFailure] skips a failed answer write left from an earlier choice, so a new choice can
 * save its setting and then answer.
 */
public fun productAnalyticsAnswerProgress(
    enabled: Boolean,
    settings: AccountSettingsState,
    appConfig: AndroidAppConfigState,
    staleAnswerFailure: Boolean = false,
): ProductAnalyticsAnswerProgress {
    val answerMutation = appConfig.mutation
    // A read that failed, for example after process death, leaves nothing in flight to wait for.
    val readFailure = (settings.content as? AccountSettingsContent.Failed)?.failure
        ?: (appConfig.content as? AndroidAppConfigContent.Failed)?.failure?.let(AccountSettingsFailure::Putio)
    val answered = (appConfig.content as? AndroidAppConfigContent.Ready)?.preferences?.productAnalyticsPromptAnswered
    return when {
        !staleAnswerFailure && answerMutation is AndroidAppConfigMutation.Failed && answerMutation.isAnswerWrite() ->
            ProductAnalyticsAnswerProgress.Failed(AccountSettingsFailure.Putio(answerMutation.failure))
        answerMutation is AndroidAppConfigMutation.Saving && answerMutation.isAnswerWrite() ->
            ProductAnalyticsAnswerProgress.Saving
        answered == true -> ProductAnalyticsAnswerProgress.Answered
        readFailure != null -> ProductAnalyticsAnswerProgress.Failed(readFailure)
        else -> settings.settingProgress(enabled)
    }
}

/** Where the analytics setting stands, once neither the answer nor a failed read decides it. */
private fun AccountSettingsState.settingProgress(enabled: Boolean): ProductAnalyticsAnswerProgress {
    val settingMutation = mutation
    return when {
        settingMutation is AccountSettingsMutation.Failed &&
            settingMutation.change.key == AccountSettingsKey.ProductAnalytics &&
            settingMutation.operation == AccountSettingsMutation.Operation.Save ->
            ProductAnalyticsAnswerProgress.Failed(settingMutation.failure)
        settingMutation is AccountSettingsMutation.Saving &&
            settingMutation.change.key == AccountSettingsKey.ProductAnalytics ->
            ProductAnalyticsAnswerProgress.Saving
        // put.io holds the choice, so the answer is about to follow.
        (content as? AccountSettingsContent.Ready)?.preferences?.productAnalyticsEnabled == enabled ->
            ProductAnalyticsAnswerProgress.Saving
        else -> ProductAnalyticsAnswerProgress.Idle
    }
}

/** Whether the last answer write failed; the prompt shows it until a new choice writes again. */
public fun AndroidAppConfigState.productAnalyticsAnswerFailed(): Boolean =
    (mutation as? AndroidAppConfigMutation.Failed)?.isAnswerWrite() == true

private fun AndroidAppConfigMutation.isAnswerWrite(): Boolean =
    when (this) {
        AndroidAppConfigMutation.Idle -> false
        is AndroidAppConfigMutation.Saving ->
            change == AndroidAppConfigChange.ProductAnalyticsPromptAnswered &&
                operation == AndroidAppConfigMutation.Operation.Save
        is AndroidAppConfigMutation.Failed ->
            change == AndroidAppConfigChange.ProductAnalyticsPromptAnswered &&
                operation == AndroidAppConfigMutation.Operation.Save
    }
