package io.putdotio.android.settings

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Diagnostics toggle as telemetry reads it: an opt-out applies at once, an opt-in once put.io confirms it. */
class AccountSettingsDiagnosticsTest {
    @Test
    fun unloadedSettingsGiveNoAnswer() {
        assertNull(AccountSettingsReducer.start().state.diagnosticsAllowed())
    }

    @Test
    fun anOptOutAppliesBeforePutioConfirmsItAndStaysWhenTheWriteFails() {
        val saving = toggle(loaded(diagnostics = true), enabled = false)
        assertEquals(false, saving.state.diagnosticsAllowed())

        val failed =
            AccountSettingsReducer.reduce(saving.state, AccountSettingsEvent.SaveFailed(saving.requestId, Failure))
        assertEquals(true, (failed.state.content as AccountSettingsContent.Ready).preferences.diagnosticsEnabled)
        assertEquals(false, failed.state.diagnosticsAllowed())
    }

    @Test
    fun anOptInWaitsForPutioToConfirmIt() {
        val saving = toggle(loaded(diagnostics = false), enabled = true)
        assertEquals(false, saving.state.diagnosticsAllowed())

        val refreshing =
            AccountSettingsReducer.reduce(saving.state, AccountSettingsEvent.SaveSucceeded(saving.requestId))
        assertEquals(false, refreshing.state.diagnosticsAllowed())

        val confirmed = AccountSettingsReducer.reduce(
            refreshing.state,
            AccountSettingsEvent.RefreshSucceeded(saving.requestId, Preferences.copy(diagnosticsEnabled = true)),
        )
        assertEquals(true, confirmed.state.diagnosticsAllowed())
    }

    @Test
    fun anotherSettingsWriteKeepsTheAnswer() {
        val loaded = loaded(diagnostics = true)
        val saving = AccountSettingsReducer.reduce(
            loaded,
            AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.History, enabled = false)),
        )
        assertEquals(true, saving.state.diagnosticsAllowed())
    }

    private class Saving(val state: AccountSettingsState, val requestId: AccountSettingsRequestId)

    private fun toggle(state: AccountSettingsState, enabled: Boolean): Saving {
        val transition = AccountSettingsReducer.reduce(
            state,
            AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.Diagnostics, enabled)),
        )
        return Saving(transition.state, (transition.effect as AccountSettingsEffect.Save).requestId)
    }

    private fun loaded(diagnostics: Boolean): AccountSettingsState {
        val start = AccountSettingsReducer.start()
        val requestId = (start.effect as AccountSettingsEffect.Load).requestId
        val preferences = Preferences.copy(diagnosticsEnabled = diagnostics)
        return AccountSettingsReducer
            .reduce(start.state, AccountSettingsEvent.LoadSucceeded(requestId, preferences))
            .state
    }

    private companion object {
        val Preferences = AccountSettingsPreferences(
            historyEnabled = true,
            trashEnabled = true,
            showSubtitles = true,
            autoSelectSubtitles = true,
        )
        val Failure = AccountSettingsFailure.Putio(PutioFailure.Unexpected(PutioConfigurationException("synthetic")))
    }
}
