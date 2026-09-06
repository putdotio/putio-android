package io.putdotio.android.settings

import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
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
        assertEquals(AccountSettingsMutation.Operation.Save, saving.operation)
        assertEquals(saving.requestId, effect.requestId)
        assertEquals(change, effect.change)
    }

    @Test
    fun historyAvailabilityChangesOnlyAfterAuthoritativeRefresh() {
        val loaded = loadedState(Preferences)
        assertTrue(checkNotNull(loaded.confirmedHistoryEnabled()))

        val change = AccountSettingsChange(AccountSettingsKey.History, enabled = false)
        val saving = AccountSettingsReducer.reduce(loaded, AccountSettingsEvent.ChangeRequested(change))
        assertNull(saving.state.confirmedHistoryEnabled())

        val requestId = (saving.effect as AccountSettingsEffect.Save).requestId
        val refreshing = AccountSettingsReducer.reduce(saving.state, AccountSettingsEvent.SaveSucceeded(requestId))
        assertNull(refreshing.state.confirmedHistoryEnabled())

        val confirmedPreferences = Preferences.copy(historyEnabled = false)
        val confirmed =
            AccountSettingsReducer.reduce(
                refreshing.state,
                AccountSettingsEvent.RefreshSucceeded(requestId, confirmedPreferences),
            )
        assertFalse(checkNotNull(confirmed.state.confirmedHistoryEnabled()))
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
                operation = AccountSettingsMutation.Operation.Save,
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
    fun acceptedSaveRetriesOnlyTheFailedAuthoritativeRefresh() {
        val change = AccountSettingsChange(AccountSettingsKey.History, enabled = false)
        val saving =
            AccountSettingsReducer.reduce(
                loadedState(Preferences),
                AccountSettingsEvent.ChangeRequested(change),
            )
        val requestId = (saving.effect as AccountSettingsEffect.Save).requestId
        val refreshing =
            AccountSettingsReducer.reduce(
                saving.state,
                AccountSettingsEvent.SaveSucceeded(requestId),
            )

        assertEquals(AccountSettingsEffect.Refresh(requestId), refreshing.effect)
        assertEquals(
            AccountSettingsMutation.Operation.Refresh,
            (refreshing.state.mutation as AccountSettingsMutation.Saving).operation,
        )

        val failure = AccountSettingsFailure.Unexpected(IllegalStateException("offline"))
        val failed =
            AccountSettingsReducer.reduce(
                refreshing.state,
                AccountSettingsEvent.RefreshFailed(requestId, failure),
            )
        val failedReady = failed.state.content as AccountSettingsContent.Ready
        assertFalse(failedReady.preferences.historyEnabled)
        assertEquals(
            AccountSettingsMutation.Operation.Refresh,
            (failed.state.mutation as AccountSettingsMutation.Failed).operation,
        )

        val retried = AccountSettingsReducer.reduce(failed.state, AccountSettingsEvent.RetryChange)
        assertTrue(retried.effect is AccountSettingsEffect.Refresh)
        assertEquals(
            AccountSettingsMutation.Operation.Refresh,
            (retried.state.mutation as AccountSettingsMutation.Saving).operation,
        )
    }

    @Test
    fun laterChangeSupersedesAFailedMutation() {
        val failure = AccountSettingsFailure.Unexpected(IllegalStateException("offline"))
        val replacement = AccountSettingsChange(AccountSettingsKey.Trash, enabled = false)

        listOf(
            failedSaveState(failure) to true,
            failedRefreshState(failure) to false,
        ).forEach { (failed, expectedHistoryEnabled) ->
            val superseded =
                AccountSettingsReducer.reduce(
                    failed,
                    AccountSettingsEvent.ChangeRequested(replacement),
                )

            assertTrue(superseded.consumed)
            assertEquals(replacement, (superseded.effect as AccountSettingsEffect.Save).change)
            val ready = superseded.state.content as AccountSettingsContent.Ready
            assertEquals(expectedHistoryEnabled, ready.preferences.historyEnabled)
            assertFalse(ready.preferences.trashEnabled)
        }
    }

    @Test
    fun laterChangeCannotHideAnAuthoritativeSessionFailure() {
        val failure =
            AccountSettingsFailure.AuthenticationRequired(
                PutioConfigurationException("invalid token"),
            )
        listOf(failedSaveState(failure), failedRefreshState(failure)).forEach { failed ->
            val replacement =
                AccountSettingsReducer.reduce(
                    failed,
                    AccountSettingsEvent.ChangeRequested(
                        AccountSettingsChange(AccountSettingsKey.Trash, enabled = false),
                    ),
                )

            assertFalse(replacement.consumed)
            assertSame(failed, replacement.state)
            assertSame(failure, replacement.state.authoritativeSessionFailure())
        }
    }

    @Test
    fun retryCannotHideAnAuthoritativeSessionFailure() {
        val failure =
            AccountSettingsFailure.AuthenticationRequired(
                PutioConfigurationException("invalid token"),
            )

        listOf(failedSaveState(failure), failedRefreshState(failure)).forEach { failed ->
            val retry = AccountSettingsReducer.reduce(failed, AccountSettingsEvent.RetryChange)

            assertFalse(retry.consumed)
            assertSame(failed, retry.state)
            assertSame(failure, retry.state.authoritativeSessionFailure())
        }
    }

    @Test
    fun ignoresStaleAndWrongPhaseResults() {
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
                AccountSettingsEvent.SaveSucceeded(AccountSettingsRequestId(99L)),
            )
        assertFalse(staleSave.consumed)
        assertSame(saving.state, staleSave.state)

        val requestId = (saving.effect as AccountSettingsEffect.Save).requestId
        val failure = AccountSettingsFailure.Unexpected(IllegalStateException("offline"))
        val staleSaveFailure =
            AccountSettingsReducer.reduce(
                saving.state,
                AccountSettingsEvent.SaveFailed(AccountSettingsRequestId(99L), failure),
            )
        assertFalse(staleSaveFailure.consumed)
        assertSame(saving.state, staleSaveFailure.state)

        listOf(
            AccountSettingsEvent.RefreshSucceeded(requestId, Preferences),
            AccountSettingsEvent.RefreshFailed(requestId, failure),
        ).forEach { wrongPhase ->
            val result = AccountSettingsReducer.reduce(saving.state, wrongPhase)
            assertFalse(result.consumed)
            assertSame(saving.state, result.state)
        }

        val refreshing =
            AccountSettingsReducer.reduce(
                saving.state,
                AccountSettingsEvent.SaveSucceeded(requestId),
            )
        listOf(
            AccountSettingsEvent.SaveSucceeded(requestId),
            AccountSettingsEvent.SaveFailed(requestId, failure),
            AccountSettingsEvent.RefreshSucceeded(AccountSettingsRequestId(99L), Preferences),
            AccountSettingsEvent.RefreshFailed(AccountSettingsRequestId(99L), failure),
        ).forEach { staleOrWrongPhase ->
            val result = AccountSettingsReducer.reduce(refreshing.state, staleOrWrongPhase)
            assertFalse(result.consumed)
            assertSame(refreshing.state, result.state)
        }
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

    @Test
    fun retryLoadCannotHideAnAuthoritativeSessionFailure() {
        val failure =
            AccountSettingsFailure.AuthenticationRequired(
                PutioConfigurationException("invalid token"),
            )
        val failed = failedLoadState(failure)

        val retry = AccountSettingsReducer.reduce(failed, AccountSettingsEvent.RetryLoad)

        assertFalse(retry.consumed)
        assertSame(failed, retry.state)
        assertSame(failure, retry.state.authoritativeSessionFailure())
    }

    @Test
    fun onlyAuthenticationFailuresCrossTheAuthoritativeSessionBoundary() {
        val invalidToken =
            AccountSettingsFailure.AuthenticationRequired(
                PutioConfigurationException("invalid token"),
            )
        val invalidScope =
            AccountSettingsFailure.AccessDenied(
                PutioConfigurationException("invalid scope"),
            )

        assertSame(invalidToken, failedLoadState(invalidToken).authoritativeSessionFailure())
        assertSame(invalidToken, failedSaveState(invalidToken).authoritativeSessionFailure())
        assertNull(failedLoadState(invalidScope).authoritativeSessionFailure())
        assertNull(failedSaveState(invalidScope).authoritativeSessionFailure())
    }

    private fun failedLoadState(failure: AccountSettingsFailure): AccountSettingsState {
        val start = AccountSettingsReducer.start()
        val requestId = (start.effect as AccountSettingsEffect.Load).requestId
        return AccountSettingsReducer.reduce(
            start.state,
            AccountSettingsEvent.LoadFailed(requestId, failure),
        ).state
    }

    private fun failedSaveState(failure: AccountSettingsFailure): AccountSettingsState {
        val saving =
            AccountSettingsReducer.reduce(
                loadedState(Preferences),
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.History, enabled = false),
                ),
            )
        val requestId = (saving.effect as AccountSettingsEffect.Save).requestId
        return AccountSettingsReducer.reduce(
            saving.state,
            AccountSettingsEvent.SaveFailed(requestId, failure),
        ).state
    }

    private fun failedRefreshState(failure: AccountSettingsFailure): AccountSettingsState {
        val saving =
            AccountSettingsReducer.reduce(
                loadedState(Preferences),
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.History, enabled = false),
                ),
            )
        val requestId = (saving.effect as AccountSettingsEffect.Save).requestId
        val refreshing =
            AccountSettingsReducer.reduce(
                saving.state,
                AccountSettingsEvent.SaveSucceeded(requestId),
            )
        return AccountSettingsReducer.reduce(
            refreshing.state,
            AccountSettingsEvent.RefreshFailed(requestId, failure),
        ).state
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
