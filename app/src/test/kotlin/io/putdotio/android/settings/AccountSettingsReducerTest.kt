package io.putdotio.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSettingsReducerTest {

    @Test
    fun startsWithAnAccountSettingsLoad() {
        val transition = AccountSettingsReducer.start()

        val loading = transition.state.content as AccountSettingsContent.Loading
        val effect = transition.effect as AccountSettingsEffect.Load
        assertEquals(loading.requestId, effect.requestId)
        assertEquals(AccountSettingsMutation.Idle, transition.state.mutation)
    }

    @Test
    fun savesAccountWideChangesOptimistically() {
        val loaded = loadedState(Preferences)
        val change = AccountSettingsChange(AccountSettingsKey.History, enabled = false)

        val transition = AccountSettingsReducer.reduce(loaded, AccountSettingsEvent.ChangeRequested(change))

        val ready = transition.state.content as AccountSettingsContent.Ready
        val saving = transition.state.mutation as AccountSettingsMutation.Saving
        val effect = transition.effect as AccountSettingsEffect.Save
        assertFalse(ready.preferences.historyEnabled)
        assertEquals(change, saving.change)
        assertEquals(saving.requestId, effect.requestId)
        assertEquals(change, effect.change)
    }

    @Test
    fun rejectsNoOpAndConcurrentChanges() {
        val loaded = loadedState(Preferences)
        val noOp =
            AccountSettingsReducer.reduce(
                loaded,
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.History, enabled = true),
                ),
            )

        assertFalse(noOp.consumed)
        assertSame(loaded, noOp.state)

        val saving =
            AccountSettingsReducer.reduce(
                loaded,
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.ShowSubtitles, enabled = false),
                ),
            )
        val concurrent =
            AccountSettingsReducer.reduce(
                saving.state,
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.Trash, enabled = false),
                ),
            )

        assertFalse(concurrent.consumed)
        assertSame(saving.state, concurrent.state)
    }

    @Test
    fun failedSaveRestoresTheServerValueAndRetriesTheExactChange() {
        val loaded = loadedState(Preferences)
        val change = AccountSettingsChange(AccountSettingsKey.AutoSelectSubtitles, enabled = false)
        val saving =
            AccountSettingsReducer.reduce(
                loaded,
                AccountSettingsEvent.ChangeRequested(change),
            )
        val requestId = (saving.effect as AccountSettingsEffect.Save).requestId
        val failure = AccountSettingsFailure.Unexpected(IllegalStateException("offline"))
        val failed =
            AccountSettingsReducer.reduce(
                saving.state,
                AccountSettingsEvent.SaveFailed(requestId, failure),
            )

        val ready = failed.state.content as AccountSettingsContent.Ready
        assertTrue(ready.preferences.autoSelectSubtitles)
        assertEquals(
            AccountSettingsMutation.Failed(
                change = change,
                failure = failure,
                previousPreferences = Preferences,
            ),
            failed.state.mutation,
        )

        val retried = AccountSettingsReducer.reduce(failed.state, AccountSettingsEvent.RetryChange)
        val retryReady = retried.state.content as AccountSettingsContent.Ready
        val retryEffect = retried.effect as AccountSettingsEffect.Save
        assertFalse(retryReady.preferences.autoSelectSubtitles)
        assertEquals(change, retryEffect.change)
        assertTrue(retryEffect.requestId.value > requestId.value)
    }

    @Test
    fun ignoresStaleLoadAndSaveResults() {
        val start = AccountSettingsReducer.start()
        val staleLoad =
            AccountSettingsReducer.reduce(
                start.state,
                AccountSettingsEvent.LoadSucceeded(AccountSettingsRequestId(99L), Preferences),
            )
        assertFalse(staleLoad.consumed)
        assertSame(start.state, staleLoad.state)

        val loaded = loadedState(Preferences)
        val saving =
            AccountSettingsReducer.reduce(
                loaded,
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.Trash, enabled = false),
                ),
            )
        val staleSave =
            AccountSettingsReducer.reduce(
                saving.state,
                AccountSettingsEvent.SaveSucceeded(AccountSettingsRequestId(99L), Preferences),
            )
        assertFalse(staleSave.consumed)
        assertSame(saving.state, staleSave.state)
    }

    @Test
    fun retriesOnlyFailedLoads() {
        val start = AccountSettingsReducer.start()
        val requestId = (start.effect as AccountSettingsEffect.Load).requestId
        val failure = AccountSettingsFailure.Unexpected(IllegalStateException("offline"))
        val failed =
            AccountSettingsReducer.reduce(
                start.state,
                AccountSettingsEvent.LoadFailed(requestId, failure),
            )
        val retry = AccountSettingsReducer.reduce(failed.state, AccountSettingsEvent.RetryLoad)

        assertTrue(retry.state.content is AccountSettingsContent.Loading)
        assertTrue(retry.effect is AccountSettingsEffect.Load)

        val duplicate = AccountSettingsReducer.reduce(retry.state, AccountSettingsEvent.RetryLoad)
        assertFalse(duplicate.consumed)
        assertSame(retry.state, duplicate.state)
    }

    private fun loadedState(preferences: AccountSettingsPreferences): AccountSettingsState {
        val start = AccountSettingsReducer.start()
        val requestId = (start.effect as AccountSettingsEffect.Load).requestId
        return AccountSettingsReducer.reduce(
            start.state,
            AccountSettingsEvent.LoadSucceeded(requestId, preferences),
        ).state
    }

    private companion object {
        val Preferences =
            AccountSettingsPreferences(
                historyEnabled = true,
                trashEnabled = true,
                showSubtitles = true,
                autoSelectSubtitles = true,
            )
    }
}
