package io.putdotio.android.settings

import io.putdotio.android.PutioFailure
import io.putdotio.android.settings.AndroidAppConfigEvent.ChangeRequested
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppConfigReducerTest {
    @Test
    fun startsWithAnAppConfigLoad() {
        val transition = AndroidAppConfigReducer.start()

        val loading = transition.state.content as AndroidAppConfigContent.Loading
        val effect = transition.effect as AndroidAppConfigEffect.Load
        assertEquals(loading.requestId, effect.requestId)
        assertEquals(AndroidAppConfigMutation.Idle, transition.state.mutation)
        assertNull(transition.state.confirmedPreferences)
    }

    @Test
    fun savesTypedChangesOptimistically() {
        listOf(
            AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4) to
                Preferences.copy(videoPlaybackType = VideoPlaybackType.Mp4),
            AndroidAppConfigChange.AutoplayNextVideo(enabled = true) to
                Preferences.copy(autoplayNextVideo = true),
        ).forEach { (change, expected) ->
            val transition = loadedState(Preferences).reduce(ChangeRequested(change))

            val saving = transition.state.mutation as AndroidAppConfigMutation.Saving
            val effect = transition.effect as AndroidAppConfigEffect.Save
            assertEquals(expected, transition.state.readyPreferences())
            assertEquals(Preferences, transition.state.confirmedPreferences)
            assertEquals(change, saving.change)
            assertEquals(Preferences, saving.previousPreferences)
            assertEquals(AndroidAppConfigMutation.Operation.Save, saving.operation)
            assertEquals(saving.requestId, effect.requestId)
            assertEquals(change, effect.change)
        }
    }

    @Test
    fun rejectsNoOpAndConcurrentChanges() {
        val loaded = loadedState(Preferences)
        val noOp = loaded.reduce(ChangeRequested(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Hls)))
        assertFalse(noOp.consumed)
        assertSame(loaded, noOp.state)

        val saving = loaded.reduce(ChangeRequested(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4)))
        val concurrent = saving.state.reduce(ChangeRequested(AndroidAppConfigChange.AutoplayNextVideo(true)))
        assertFalse(concurrent.consumed)
        assertSame(saving.state, concurrent.state)
    }

    @Test
    fun failedSaveRollsBackAndRetriesTheExactChange() {
        val change = AndroidAppConfigChange.AutoplayNextVideo(enabled = true)
        val saving = requestChange(change)
        val requestId = saving.saveRequestId()
        val failed = saving.state.reduce(AndroidAppConfigEvent.SaveFailed(requestId, Offline))

        assertEquals(Preferences, failed.state.readyPreferences())
        assertEquals(Preferences, failed.state.confirmedPreferences)
        assertEquals(
            AndroidAppConfigMutation.Failed(
                change = change,
                failure = Offline,
                previousPreferences = Preferences,
                operation = AndroidAppConfigMutation.Operation.Save,
            ),
            failed.state.mutation,
        )

        val retried = failed.state.reduce(AndroidAppConfigEvent.RetryChange)
        val retryEffect = retried.effect as AndroidAppConfigEffect.Save
        assertTrue(retried.state.readyPreferences().autoplayNextVideo)
        assertEquals(Preferences, retried.state.confirmedPreferences)
        assertEquals(change, retryEffect.change)
        assertTrue(retryEffect.requestId.value > requestId.value)
        assertEquals(
            AndroidAppConfigMutation.Operation.Save,
            (retried.state.mutation as AndroidAppConfigMutation.Saving).operation,
        )
    }

    @Test
    fun acceptedSaveRefreshesAuthoritativeConfig() {
        val saving = requestChange(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4))
        val requestId = saving.saveRequestId()

        val refreshing = saving.state.reduce(AndroidAppConfigEvent.SaveSucceeded(requestId))
        assertEquals(AndroidAppConfigEffect.Refresh(requestId), refreshing.effect)
        assertEquals(
            AndroidAppConfigMutation.Operation.Refresh,
            (refreshing.state.mutation as AndroidAppConfigMutation.Saving).operation,
        )

        val authoritative = Preferences.copy(videoPlaybackType = VideoPlaybackType.Mp4, autoplayNextVideo = true)
        val refreshed = refreshing.state.reduce(AndroidAppConfigEvent.RefreshSucceeded(requestId, authoritative))
        assertEquals(authoritative, refreshed.state.readyPreferences())
        assertEquals(authoritative, refreshed.state.confirmedPreferences)
        assertEquals(AndroidAppConfigMutation.Idle, refreshed.state.mutation)
    }

    @Test
    fun failedRefreshRetainsAcceptedValueAndRetriesOnlyTheRead() {
        val change = AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4)
        val saving = requestChange(change)
        val requestId = saving.saveRequestId()
        val refreshing = saving.state.reduce(AndroidAppConfigEvent.SaveSucceeded(requestId))
        val failed = refreshing.state.reduce(AndroidAppConfigEvent.RefreshFailed(requestId, Offline))

        assertEquals(VideoPlaybackType.Mp4, failed.state.readyPreferences().videoPlaybackType)
        assertEquals(Preferences, failed.state.confirmedPreferences)
        assertEquals(
            AndroidAppConfigMutation.Operation.Refresh,
            (failed.state.mutation as AndroidAppConfigMutation.Failed).operation,
        )

        val retried = failed.state.reduce(AndroidAppConfigEvent.RetryChange)
        val retryEffect = retried.effect as AndroidAppConfigEffect.Refresh
        assertTrue(retryEffect.requestId.value > requestId.value)
        assertEquals(VideoPlaybackType.Mp4, retried.state.readyPreferences().videoPlaybackType)
    }

    @Test
    fun laterChangeSupersedesOnlyRetryableMutationFailures() {
        val replacement = AndroidAppConfigChange.AutoplayNextVideo(enabled = true)

        listOf(failedSaveState(Offline), failedRefreshState(Offline)).forEach { failed ->
            val superseded = failed.reduce(ChangeRequested(replacement))

            assertTrue(superseded.consumed)
            assertEquals(replacement, (superseded.effect as AndroidAppConfigEffect.Save).change)
            assertTrue(superseded.state.readyPreferences().autoplayNextVideo)
            assertEquals(Preferences, superseded.state.confirmedPreferences)
        }

        val failedAuth = failedSaveState(InvalidToken)
        val rejected = failedAuth.reduce(ChangeRequested(replacement))
        assertFalse(rejected.consumed)
        assertSame(failedAuth, rejected.state)
        assertSame(InvalidToken, rejected.state.authoritativeSessionFailure())
    }

    @Test
    fun retriesOnlyRetryableFailedLoads() {
        val failed = failedLoadState(Offline)
        val retry = failed.reduce(AndroidAppConfigEvent.RetryLoad)

        assertTrue(retry.state.content is AndroidAppConfigContent.Loading)
        assertTrue(retry.effect is AndroidAppConfigEffect.Load)

        val duplicate = retry.state.reduce(AndroidAppConfigEvent.RetryLoad)
        assertFalse(duplicate.consumed)
        assertSame(retry.state, duplicate.state)

        val failedAuth = failedLoadState(InvalidToken)
        val rejected = failedAuth.reduce(AndroidAppConfigEvent.RetryLoad)
        assertFalse(rejected.consumed)
        assertSame(failedAuth, rejected.state)
        assertSame(InvalidToken, rejected.state.authoritativeSessionFailure())
    }

    @Test
    fun ignoresStaleAndWrongPhaseResults() {
        val start = AndroidAppConfigReducer.start()
        val initialRequestId = (start.effect as AndroidAppConfigEffect.Load).requestId
        listOf(
            AndroidAppConfigEvent.LoadSucceeded(AndroidAppConfigRequestId(99L), Preferences),
            AndroidAppConfigEvent.LoadFailed(AndroidAppConfigRequestId(99L), Offline),
        ).forEach { staleLoad ->
            val result = start.state.reduce(staleLoad)
            assertFalse(result.consumed)
            assertSame(start.state, result.state)
        }

        val loaded = start.state.reduce(AndroidAppConfigEvent.LoadSucceeded(initialRequestId, Preferences)).state
        val saving = loaded.reduce(ChangeRequested(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4)))
        val requestId = saving.saveRequestId()
        listOf(
            AndroidAppConfigEvent.SaveSucceeded(AndroidAppConfigRequestId(99L)),
            AndroidAppConfigEvent.SaveFailed(AndroidAppConfigRequestId(99L), Offline),
            AndroidAppConfigEvent.RefreshSucceeded(requestId, Preferences),
            AndroidAppConfigEvent.RefreshFailed(requestId, Offline),
        ).forEach { staleOrWrongPhase ->
            val result = saving.state.reduce(staleOrWrongPhase)
            assertFalse(result.consumed)
            assertSame(saving.state, result.state)
        }

        val refreshing = saving.state.reduce(AndroidAppConfigEvent.SaveSucceeded(requestId))
        listOf(
            AndroidAppConfigEvent.SaveSucceeded(requestId),
            AndroidAppConfigEvent.SaveFailed(requestId, Offline),
            AndroidAppConfigEvent.RefreshSucceeded(AndroidAppConfigRequestId(99L), Preferences),
            AndroidAppConfigEvent.RefreshFailed(AndroidAppConfigRequestId(99L), Offline),
        ).forEach { staleOrWrongPhase ->
            val result = refreshing.state.reduce(staleOrWrongPhase)
            assertFalse(result.consumed)
            assertSame(refreshing.state, result.state)
        }
    }

    @Test
    fun onlyAuthenticationFailuresCrossTheAuthoritativeSessionBoundary() {
        assertSame(InvalidToken, failedLoadState(InvalidToken).authoritativeSessionFailure())
        assertSame(InvalidToken, failedSaveState(InvalidToken).authoritativeSessionFailure())
        assertNull(failedLoadState(Offline).authoritativeSessionFailure())
        assertNull(failedSaveState(Offline).authoritativeSessionFailure())
    }

    private fun AndroidAppConfigState.reduce(event: AndroidAppConfigEvent): AndroidAppConfigTransition =
        AndroidAppConfigReducer.reduce(this, event)

    private fun AndroidAppConfigTransition.saveRequestId(): AndroidAppConfigRequestId =
        (effect as AndroidAppConfigEffect.Save).requestId

    private fun requestChange(change: AndroidAppConfigChange): AndroidAppConfigTransition =
        loadedState(Preferences).reduce(ChangeRequested(change))

    private fun failedLoadState(failure: PutioFailure): AndroidAppConfigState {
        val start = AndroidAppConfigReducer.start()
        val requestId = (start.effect as AndroidAppConfigEffect.Load).requestId
        return start.state.reduce(AndroidAppConfigEvent.LoadFailed(requestId, failure)).state
    }

    private fun failedSaveState(failure: PutioFailure): AndroidAppConfigState {
        val saving = requestChange(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4))
        val requestId = saving.saveRequestId()
        return saving.state.reduce(AndroidAppConfigEvent.SaveFailed(requestId, failure)).state
    }

    private fun failedRefreshState(failure: PutioFailure): AndroidAppConfigState {
        val saving = requestChange(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4))
        val requestId = saving.saveRequestId()
        val refreshing = saving.state.reduce(AndroidAppConfigEvent.SaveSucceeded(requestId))
        return refreshing.state.reduce(AndroidAppConfigEvent.RefreshFailed(requestId, failure)).state
    }

    private fun loadedState(preferences: AndroidAppConfigPreferences): AndroidAppConfigState {
        val start = AndroidAppConfigReducer.start()
        val requestId = (start.effect as AndroidAppConfigEffect.Load).requestId
        return start.state.reduce(AndroidAppConfigEvent.LoadSucceeded(requestId, preferences)).state
    }

    private fun AndroidAppConfigState.readyPreferences(): AndroidAppConfigPreferences =
        (content as AndroidAppConfigContent.Ready).preferences

    private companion object {
        val Preferences = DefaultAndroidAppConfigPreferences
        val Offline = PutioFailure.Unexpected(IllegalStateException("offline"))
        val InvalidToken = PutioFailure.AuthenticationRequired(PutioConfigurationException("invalid token"))
    }
}
