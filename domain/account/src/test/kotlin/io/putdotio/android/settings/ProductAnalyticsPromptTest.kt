package io.putdotio.android.settings

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.config.AppConfig
import io.putdotio.sdk.config.AppConfigUpdate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductAnalyticsPromptTest {
    @Test
    fun readsTheAnswerSoOnlyAReadableNoAsks() {
        fun answered(value: JsonElement?) =
            AppConfig(listOfNotNull(value?.let { PROMPT_KEY to it }).toMap())
                .toAndroidPreferences().productAnalyticsPromptAnswered

        assertEquals(false, answered(null))
        assertEquals(false, answered(JsonPrimitive(false)))
        assertEquals(true, answered(JsonPrimitive(true)))
        assertEquals(true, answered(JsonPrimitive("false")))
        assertEquals(true, answered(JsonArray(emptyList())))
    }

    @Test
    fun theAnswerWritesTheFlatDottedKey() {
        assertEquals(
            AppConfigUpdate(PROMPT_KEY, JsonPrimitive(true)),
            AndroidAppConfigChange.ProductAnalyticsPromptAnswered.toUpdate(),
        )
    }

    @Test
    fun asksWhileAnalyticsIsOffAndUnansweredOnceBothReadsLand() {
        val off = settings(enabled = false)
        assertEquals(true, productAnalyticsPromptDue(off, config(answered = false)))
        assertEquals(false, productAnalyticsPromptDue(settings(enabled = true), config(answered = false)))
        assertEquals(false, productAnalyticsPromptDue(off, config(answered = true)))
        assertEquals(null, productAnalyticsPromptDue(AccountSettingsReducer.start().state, config(answered = false)))
        assertEquals(null, productAnalyticsPromptDue(off, AndroidAppConfigReducer.start().state))
    }

    @Test
    fun allowSavesTheSettingFirstAndAnswersOnlyOncePutioHoldsIt() {
        val off = settings(enabled = false)
        val unanswered = config(answered = false)
        val step = productAnalyticsAnswerStep(enabled = true, off, unanswered)
        assertEquals(ProductAnalyticsAnswerStep.SaveSetting(allow), step)

        val saving = off.on(allow)
        assertEquals(ProductAnalyticsAnswerStep.Wait, productAnalyticsAnswerStep(enabled = true, saving, unanswered))
        assertEquals(ProductAnalyticsAnswerProgress.Saving, productAnalyticsAnswerProgress(true, saving, unanswered))

        val accepted = saving.on(AccountSettingsEvent.SaveSucceeded(saving.savingId()))
        assertEquals(markAnswered, productAnalyticsAnswerStep(enabled = true, accepted, unanswered))
    }

    @Test
    fun aChoiceRestoredWithNothingInFlightCanBeAnsweredAgain() {
        // The process died after Allow, before put.io took the setting.
        assertEquals(
            ProductAnalyticsAnswerProgress.Idle,
            productAnalyticsAnswerProgress(true, settings(enabled = false), config(answered = false)),
        )
    }

    @Test
    fun aFailedSettingRecordsNoAnswer() {
        val off = settings(enabled = false)
        val unanswered = config(answered = false)
        val saving = off.on(allow)
        val failure = AccountSettingsFailure.Putio(PutioFailure.NetworkUnavailable(RuntimeException("offline")))
        val failed = saving.on(AccountSettingsEvent.SaveFailed(saving.savingId(), failure))

        assertEquals(
            ProductAnalyticsAnswerProgress.Failed(failure),
            productAnalyticsAnswerProgress(true, failed, unanswered),
        )
        assertEquals(emptyList<Boolean>(), choices(off, saving, failed))
        // Trying again saves the setting again rather than skipping to the answer.
        assertEquals(
            ProductAnalyticsAnswerStep.SaveSetting(allow),
            productAnalyticsAnswerStep(enabled = true, failed, unanswered),
        )
    }

    @Test
    fun anAcceptedChoiceFromAnySurfaceAnswersThePrompt() {
        val off = settings(enabled = false)
        val saving = off.on(allow)
        val accepted = saving.on(AccountSettingsEvent.SaveSucceeded(saving.savingId()))
        val otherSaving = off.on(
            AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.History, false)),
        )
        val otherAccepted = otherSaving.on(AccountSettingsEvent.SaveSucceeded(otherSaving.savingId()))

        assertEquals(listOf(true), choices(off, saving, accepted, otherSaving, otherAccepted))
    }

    @Test
    fun allowStillSavesTheSettingWhenTheAnswerLandedElsewhere() {
        assertEquals(
            ProductAnalyticsAnswerStep.SaveSetting(allow),
            productAnalyticsAnswerStep(enabled = true, settings(enabled = false), config(answered = true)),
        )
    }

    @Test
    fun aPendingAnswerWaitsForABusyConfigAndStopsOnceRecorded() {
        val unanswered = config(answered = false)
        assertEquals(true, unanswered.canRecordProductAnalyticsAnswer())
        assertEquals(false, AndroidAppConfigReducer.start().state.canRecordProductAnalyticsAnswer())
        val busy = unanswered.on(
            AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.AutoplayNextVideo(true)),
        )
        assertEquals(false, busy.canRecordProductAnalyticsAnswer())
        assertEquals(null, config(answered = true).canRecordProductAnalyticsAnswer())

        val answering = unanswered.on(markAnswered.event)
        val failed = answering.on(
            AndroidAppConfigEvent.SaveFailed(answering.savingId(), PutioFailure.Unexpected(RuntimeException("503"))),
        )
        assertEquals(null, failed.canRecordProductAnalyticsAnswer())
    }

    @Test
    fun dontAllowOnAnAccountAlreadyOffOnlyRecordsTheAnswer() {
        assertEquals(
            markAnswered,
            productAnalyticsAnswerStep(enabled = false, settings(enabled = false), config(answered = false)),
        )
    }

    @Test
    fun aFailedAnswerShowsAndALandedOneCloses() {
        val accepted = settings(enabled = true)
        val answering = config(answered = false).on(markAnswered.event)
        assertEquals(ProductAnalyticsAnswerProgress.Saving, productAnalyticsAnswerProgress(true, accepted, answering))

        val failure = PutioFailure.Unexpected(RuntimeException("503"))
        val failed = answering.on(AndroidAppConfigEvent.SaveFailed(answering.savingId(), failure))
        assertEquals(
            ProductAnalyticsAnswerProgress.Failed(AccountSettingsFailure.Putio(failure)),
            productAnalyticsAnswerProgress(true, accepted, failed),
        )
        assertEquals(markAnswered, productAnalyticsAnswerStep(enabled = true, accepted, failed))

        val landed = answering.on(AndroidAppConfigEvent.SaveSucceeded(answering.savingId()))
        assertEquals(ProductAnalyticsAnswerProgress.Answered, productAnalyticsAnswerProgress(true, accepted, landed))
    }

    private fun settings(enabled: Boolean) =
        readyAccountSettingsState(DefaultAccountSettingsPreferences.copy(productAnalyticsEnabled = enabled))

    private fun config(answered: Boolean) =
        readyAndroidAppConfigState(AndroidAppConfigPreferences(productAnalyticsPromptAnswered = answered))

    private fun choices(vararg states: AccountSettingsState) =
        runBlocking { flowOf(*states).acceptedProductAnalyticsChoices().toList() }

    private fun AccountSettingsState.on(event: AccountSettingsEvent) = AccountSettingsReducer.reduce(this, event).state

    private fun AndroidAppConfigState.on(event: AndroidAppConfigEvent) =
        AndroidAppConfigReducer.reduce(this, event).state

    private fun AccountSettingsState.savingId() = (mutation as AccountSettingsMutation.Saving).requestId

    private fun AndroidAppConfigState.savingId() = (mutation as AndroidAppConfigMutation.Saving).requestId

    private val allow =
        AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.ProductAnalytics, true))

    private val markAnswered =
        ProductAnalyticsAnswerStep.MarkAnswered(
            AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.ProductAnalyticsPromptAnswered),
        )

    private companion object {
        // iOS AppConfig.CodingKeys.productAnalyticsPromptAnswered
        const val PROMPT_KEY = "dismissed.product_analytics_prompt"
    }
}
