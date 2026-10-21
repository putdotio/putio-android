package io.putdotio.android.settings

import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesSort
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSettingsReducerTest {
    @Test
    fun savesAccountWideChangesOptimistically() {
        val change = AccountSettingsChange(AccountSettingsKey.History, enabled = false)

        val transition = loadedState(Preferences).reduce(AccountSettingsEvent.ChangeRequested(change))

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

        val saving = loaded.reduce(toggle(AccountSettingsKey.History, false))
        assertNull(saving.state.confirmedHistoryEnabled())

        val requestId = saving.saveRequestId()
        val refreshing = saving.state.reduce(AccountSettingsEvent.SaveSucceeded(requestId))
        assertNull(refreshing.state.confirmedHistoryEnabled())

        val confirmed = refreshing.state.reduce(
            AccountSettingsEvent.RefreshSucceeded(requestId, Preferences.copy(historyEnabled = false)),
        )
        assertFalse(checkNotNull(confirmed.state.confirmedHistoryEnabled()))
    }

    @Test
    fun rejectsNoOpAndConcurrentChanges() {
        val loaded = loadedState(Preferences)
        val noOp = loaded.reduce(toggle(AccountSettingsKey.History, true))
        assertFalse(noOp.consumed)
        assertSame(loaded, noOp.state)

        val saving = loaded.reduce(toggle(AccountSettingsKey.ShowSubtitles, false))
        val concurrent = saving.state.reduce(toggle(AccountSettingsKey.Trash, false))
        assertFalse(concurrent.consumed)
        assertSame(saving.state, concurrent.state)
    }

    @Test
    fun failedSaveRestoresTheServerValueAndRetriesTheExactChange() {
        val change = AccountSettingsChange(AccountSettingsKey.AutoSelectSubtitles, enabled = false)
        val saving = loadedState(Preferences).reduce(AccountSettingsEvent.ChangeRequested(change))
        val requestId = saving.saveRequestId()
        val failed = saving.state.reduce(AccountSettingsEvent.SaveFailed(requestId, Offline))

        val ready = failed.state.content as AccountSettingsContent.Ready
        assertTrue(ready.preferences.autoSelectSubtitles)
        assertEquals(
            AccountSettingsMutation.Failed(
                change = change,
                failure = Offline,
                previousPreferences = Preferences,
                operation = AccountSettingsMutation.Operation.Save,
            ),
            failed.state.mutation,
        )

        val retried = failed.state.reduce(AccountSettingsEvent.RetryChange)
        val retryReady = retried.state.content as AccountSettingsContent.Ready
        val retryEffect = retried.effect as AccountSettingsEffect.Save
        assertFalse(retryReady.preferences.autoSelectSubtitles)
        assertEquals(change, retryEffect.change)
        assertTrue(retryEffect.requestId.value > requestId.value)
    }

    @Test
    fun acceptedSaveRetriesOnlyTheFailedAuthoritativeRefresh() {
        val change = AccountSettingsChange(AccountSettingsKey.History, enabled = false)
        val saving = loadedState(Preferences).reduce(AccountSettingsEvent.ChangeRequested(change))
        val requestId = saving.saveRequestId()
        val refreshing = saving.state.reduce(AccountSettingsEvent.SaveSucceeded(requestId))

        assertEquals(AccountSettingsEffect.Refresh(requestId), refreshing.effect)
        assertEquals(
            AccountSettingsMutation.Operation.Refresh,
            (refreshing.state.mutation as AccountSettingsMutation.Saving).operation,
        )

        val failed = refreshing.state.reduce(AccountSettingsEvent.RefreshFailed(requestId, Offline))
        val failedReady = failed.state.content as AccountSettingsContent.Ready
        assertFalse(failedReady.preferences.historyEnabled)
        assertEquals(
            AccountSettingsMutation.Operation.Refresh,
            (failed.state.mutation as AccountSettingsMutation.Failed).operation,
        )

        val retried = failed.state.reduce(AccountSettingsEvent.RetryChange)
        assertTrue(retried.effect is AccountSettingsEffect.Refresh)
        assertEquals(
            AccountSettingsMutation.Operation.Refresh,
            (retried.state.mutation as AccountSettingsMutation.Saving).operation,
        )
    }

    @Test
    fun laterChangeSupersedesAFailedMutation() {
        val replacement = AccountSettingsChange(AccountSettingsKey.Trash, enabled = false)

        listOf(
            failedSaveState(Offline) to true,
            failedRefreshState(Offline) to false,
        ).forEach { (failed, expectedHistoryEnabled) ->
            val superseded = failed.reduce(AccountSettingsEvent.ChangeRequested(replacement))

            assertTrue(superseded.consumed)
            assertEquals(replacement, (superseded.effect as AccountSettingsEffect.Save).change)
            val ready = superseded.state.content as AccountSettingsContent.Ready
            assertEquals(expectedHistoryEnabled, ready.preferences.historyEnabled)
            assertFalse(ready.preferences.trashEnabled)
        }
    }

    @Test
    fun noRetryOrLaterChangeCanHideAnAuthoritativeSessionFailure() {
        val events = listOf(
            AccountSettingsEvent.RetryLoad,
            AccountSettingsEvent.RetryChange,
            toggle(AccountSettingsKey.Trash, false),
        )
        listOf(failedLoadState(InvalidToken), failedSaveState(InvalidToken), failedRefreshState(InvalidToken))
            .forEach { failed ->
                events.forEach { event ->
                    val result = failed.reduce(event)

                    assertFalse(result.consumed)
                    assertSame(failed, result.state)
                    assertSame(InvalidToken, result.state.authoritativeSessionFailure())
                }
            }
    }

    @Test
    fun ignoresStaleAndWrongPhaseResults() {
        val start = AccountSettingsReducer.start()
        val staleLoad =
            start.state.reduce(AccountSettingsEvent.LoadSucceeded(AccountSettingsRequestId(99L), Preferences))
        assertFalse(staleLoad.consumed)
        assertSame(start.state, staleLoad.state)

        val saving = loadedState(Preferences).reduce(toggle(AccountSettingsKey.Trash, false))
        val requestId = saving.saveRequestId()
        listOf(
            AccountSettingsEvent.SaveSucceeded(AccountSettingsRequestId(99L)),
            AccountSettingsEvent.SaveFailed(AccountSettingsRequestId(99L), Offline),
            AccountSettingsEvent.RefreshSucceeded(requestId, Preferences),
            AccountSettingsEvent.RefreshFailed(requestId, Offline),
        ).forEach { staleOrWrongPhase ->
            val result = saving.state.reduce(staleOrWrongPhase)
            assertFalse(result.consumed)
            assertSame(saving.state, result.state)
        }

        val refreshing = saving.state.reduce(AccountSettingsEvent.SaveSucceeded(requestId))
        listOf(
            AccountSettingsEvent.SaveSucceeded(requestId),
            AccountSettingsEvent.SaveFailed(requestId, Offline),
            AccountSettingsEvent.RefreshSucceeded(AccountSettingsRequestId(99L), Preferences),
            AccountSettingsEvent.RefreshFailed(AccountSettingsRequestId(99L), Offline),
        ).forEach { staleOrWrongPhase ->
            val result = refreshing.state.reduce(staleOrWrongPhase)
            assertFalse(result.consumed)
            assertSame(refreshing.state, result.state)
        }
    }

    @Test
    fun retriesOnlyFailedLoads() {
        val retry = failedLoadState(Offline).reduce(AccountSettingsEvent.RetryLoad)

        assertTrue(retry.state.content is AccountSettingsContent.Loading)
        assertTrue(retry.effect is AccountSettingsEffect.Load)

        val duplicate = retry.state.reduce(AccountSettingsEvent.RetryLoad)
        assertFalse(duplicate.consumed)
        assertSame(retry.state, duplicate.state)
    }

    @Test
    fun onlyAuthenticationFailuresCrossTheAuthoritativeSessionBoundary() {
        val invalidScope =
            AccountSettingsFailure.Putio(PutioFailure.AccessDenied(PutioConfigurationException("invalid scope")))

        assertSame(InvalidToken, failedLoadState(InvalidToken).authoritativeSessionFailure())
        assertSame(InvalidToken, failedSaveState(InvalidToken).authoritativeSessionFailure())
        assertNull(failedLoadState(invalidScope).authoritativeSessionFailure())
        assertNull(failedSaveState(invalidScope).authoritativeSessionFailure())
    }

    @Test
    fun routeChangesSaveOptimisticallyAndIdenticalRoutesAreIgnored() {
        val loaded = loadedState(Preferences)
        val same =
            loaded.reduce(AccountSettingsEvent.ChangeRequested(AccountSettingsChange.Route(TunnelRouteName.DEFAULT)))
        assertFalse(same.consumed)
        assertNull(same.effect)

        val change = AccountSettingsChange.Route(TunnelRouteName("cdn77"))
        val saving = loaded.reduce(AccountSettingsEvent.ChangeRequested(change))
        val ready = saving.state.content as AccountSettingsContent.Ready
        assertEquals(TunnelRouteName("cdn77"), ready.preferences.tunnelRoute)
        assertEquals(change, (saving.effect as AccountSettingsEffect.Save).change)

        val failed = saving.state.reduce(AccountSettingsEvent.SaveFailed(saving.saveRequestId(), Offline))
        val rolledBack = failed.state.content as AccountSettingsContent.Ready
        assertEquals(TunnelRouteName.DEFAULT, rolledBack.preferences.tunnelRoute)
    }

    @Test
    fun sortChangesSaveOptimisticallyRollBackAndOnlyUnconfirmTheirOwnKey() {
        val loaded = loadedState(Preferences.copy(defaultSort = FilesSort.NAME_ASCENDING))
        val same =
            loaded.reduce(AccountSettingsEvent.ChangeRequested(AccountSettingsChange.Sort(FilesSort.NAME_ASCENDING)))
        assertFalse(same.consumed)

        val saving = loaded.reduce(
            AccountSettingsEvent.ChangeRequested(AccountSettingsChange.Sort(FilesSort.DATE_ADDED_DESCENDING)),
        )
        val ready = saving.state.content as AccountSettingsContent.Ready
        assertEquals(FilesSort.DATE_ADDED_DESCENDING, ready.preferences.defaultSort)
        assertTrue(checkNotNull(saving.state.confirmedTrashEnabled()))
        assertNull(saving.state.confirmedDefaultSort())
        assertEquals(ConfirmedDefaultSort(FilesSort.NAME_ASCENDING), loaded.confirmedDefaultSort())
        assertEquals(ConfirmedDefaultSort(null), loadedState(Preferences).confirmedDefaultSort())

        // The server accepted the write; a failed follow-up refresh must not hide the new value.
        val requestId = saving.saveRequestId()
        val refreshing = saving.state.reduce(AccountSettingsEvent.SaveSucceeded(requestId))
        assertEquals(ConfirmedDefaultSort(FilesSort.DATE_ADDED_DESCENDING), refreshing.state.confirmedDefaultSort())
        val refreshFailed = refreshing.state.reduce(AccountSettingsEvent.RefreshFailed(requestId, Offline))
        assertEquals(ConfirmedDefaultSort(FilesSort.DATE_ADDED_DESCENDING), refreshFailed.state.confirmedDefaultSort())

        val failed = saving.state.reduce(AccountSettingsEvent.SaveFailed(requestId, Offline))
        val rolledBack = failed.state.content as AccountSettingsContent.Ready
        assertEquals(FilesSort.NAME_ASCENDING, rolledBack.preferences.defaultSort)
        assertTrue(checkNotNull(failed.state.confirmedTrashEnabled()))
        assertTrue(checkNotNull(failed.state.confirmedHistoryEnabled()))
    }

    private fun toggle(key: AccountSettingsKey, enabled: Boolean): AccountSettingsEvent =
        AccountSettingsEvent.ChangeRequested(AccountSettingsChange(key, enabled))

    private fun AccountSettingsState.reduce(event: AccountSettingsEvent): AccountSettingsTransition =
        AccountSettingsReducer.reduce(this, event)

    private fun AccountSettingsTransition.saveRequestId(): AccountSettingsRequestId =
        (effect as AccountSettingsEffect.Save).requestId

    private fun loadedState(preferences: AccountSettingsPreferences): AccountSettingsState {
        val start = AccountSettingsReducer.start()
        val requestId = (start.effect as AccountSettingsEffect.Load).requestId
        return start.state.reduce(AccountSettingsEvent.LoadSucceeded(requestId, preferences)).state
    }

    private fun failedLoadState(failure: AccountSettingsFailure): AccountSettingsState {
        val start = AccountSettingsReducer.start()
        val requestId = (start.effect as AccountSettingsEffect.Load).requestId
        return start.state.reduce(AccountSettingsEvent.LoadFailed(requestId, failure)).state
    }

    private fun savingHistoryOff(): AccountSettingsTransition =
        loadedState(Preferences).reduce(toggle(AccountSettingsKey.History, false))

    private fun failedSaveState(failure: AccountSettingsFailure): AccountSettingsState {
        val saving = savingHistoryOff()
        return saving.state.reduce(AccountSettingsEvent.SaveFailed(saving.saveRequestId(), failure)).state
    }

    private fun failedRefreshState(failure: AccountSettingsFailure): AccountSettingsState {
        val saving = savingHistoryOff()
        val requestId = saving.saveRequestId()
        val refreshing = saving.state.reduce(AccountSettingsEvent.SaveSucceeded(requestId))
        return refreshing.state.reduce(AccountSettingsEvent.RefreshFailed(requestId, failure)).state
    }

    private companion object {
        val Preferences = DefaultAccountSettingsPreferences
        val Offline = AccountSettingsFailure.Putio(PutioFailure.Unexpected(IllegalStateException("offline")))
        val InvalidToken = AccountSettingsFailure.Putio(
            PutioFailure.AuthenticationRequired(PutioConfigurationException("invalid token")),
        )
    }
}
