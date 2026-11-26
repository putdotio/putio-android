package io.putdotio.android.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

public fun AndroidAppConfigState.canRecordProductAnalyticsAnswer(): Boolean? {
    val config = (content as? AndroidAppConfigContent.Ready)?.preferences
    return when {
        config?.productAnalyticsPromptAnswered == true || productAnalyticsAnswerFailed() -> null
        config == null -> false
        else -> mutation !is AndroidAppConfigMutation.Saving
    }
}

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

public sealed interface ProductAnalyticsAnswerProgress {
    public data object Idle : ProductAnalyticsAnswerProgress

    public data object Saving : ProductAnalyticsAnswerProgress

    public data class Failed(
        val failure: AccountSettingsFailure,
    ) : ProductAnalyticsAnswerProgress

    public data object Answered : ProductAnalyticsAnswerProgress
}

public fun productAnalyticsAnswerProgress(
    enabled: Boolean,
    settings: AccountSettingsState,
    appConfig: AndroidAppConfigState,
    staleAnswerFailure: Boolean = false,
): ProductAnalyticsAnswerProgress {
    val answerMutation = appConfig.mutation
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
        (content as? AccountSettingsContent.Ready)?.preferences?.productAnalyticsEnabled == enabled ->
            ProductAnalyticsAnswerProgress.Saving
        else -> ProductAnalyticsAnswerProgress.Idle
    }
}

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
