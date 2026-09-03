package io.putdotio.android.settings

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
            val transition =
                AndroidAppConfigReducer.reduce(
                    loadedState(Preferences),
                    AndroidAppConfigEvent.ChangeRequested(change),
                )

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
        val noOp =
            AndroidAppConfigReducer.reduce(
                loaded,
                AndroidAppConfigEvent.ChangeRequested(
                    AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Hls),
                ),
            )
        assertFalse(noOp.consumed)
        assertSame(loaded, noOp.state)

        val saving =
            AndroidAppConfigReducer.reduce(
                loaded,
                AndroidAppConfigEvent.ChangeRequested(
                    AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4),
                ),
            )
        val concurrent =
            AndroidAppConfigReducer.reduce(
                saving.state,
                AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.AutoplayNextVideo(true)),
            )
        assertFalse(concurrent.consumed)
        assertSame(saving.state, concurrent.state)
    }

    @Test
    fun failedSaveRollsBackAndRetriesTheExactChange() {
        val change = AndroidAppConfigChange.AutoplayNextVideo(enabled = true)
        val saving = requestChange(change)
        val requestId = (saving.effect as AndroidAppConfigEffect.Save).requestId
        val failure = AndroidAppConfigFailure.Unexpected(IllegalStateException("offline"))
        val failed =
            AndroidAppConfigReducer.reduce(
                saving.state,
                AndroidAppConfigEvent.SaveFailed(requestId, failure),
            )

        assertEquals(Preferences, failed.state.readyPreferences())
        assertEquals(
            AndroidAppConfigMutation.Failed(
                change = change,
                failure = failure,
                previousPreferences = Preferences,
                operation = AndroidAppConfigMutation.Operation.Save,
            ),
            failed.state.mutation,
        )

        val retried = AndroidAppConfigReducer.reduce(failed.state, AndroidAppConfigEvent.RetryChange)
        val retryEffect = retried.effect as AndroidAppConfigEffect.Save
        assertTrue(retried.state.readyPreferences().autoplayNextVideo)
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
        val requestId = (saving.effect as AndroidAppConfigEffect.Save).requestId

        val refreshing =
            AndroidAppConfigReducer.reduce(
                saving.state,
                AndroidAppConfigEvent.SaveSucceeded(requestId),
            )
        assertEquals(AndroidAppConfigEffect.Refresh(requestId), refreshing.effect)
        assertEquals(
            AndroidAppConfigMutation.Operation.Refresh,
            (refreshing.state.mutation as AndroidAppConfigMutation.Saving).operation,
        )

        val authoritative = Preferences.copy(videoPlaybackType = VideoPlaybackType.Mp4, autoplayNextVideo = true)
        val refreshed =
            AndroidAppConfigReducer.reduce(
                refreshing.state,
                AndroidAppConfigEvent.RefreshSucceeded(requestId, authoritative),
            )
        assertEquals(authoritative, refreshed.state.readyPreferences())
        assertEquals(authoritative, refreshed.state.confirmedPreferences)
        assertEquals(AndroidAppConfigMutation.Idle, refreshed.state.mutation)
    }

    @Test
    fun failedRefreshRetainsAcceptedValueAndRetriesOnlyTheRead() {
        val change = AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4)
        val saving = requestChange(change)
        val requestId = (saving.effect as AndroidAppConfigEffect.Save).requestId
        val refreshing =
            AndroidAppConfigReducer.reduce(
                saving.state,
                AndroidAppConfigEvent.SaveSucceeded(requestId),
            )
        val failure = AndroidAppConfigFailure.Unexpected(IllegalStateException("offline"))
        val failed =
            AndroidAppConfigReducer.reduce(
                refreshing.state,
                AndroidAppConfigEvent.RefreshFailed(requestId, failure),
            )

        assertEquals(VideoPlaybackType.Mp4, failed.state.readyPreferences().videoPlaybackType)
        assertEquals(Preferences, failed.state.confirmedPreferences)
        assertEquals(
            AndroidAppConfigMutation.Operation.Refresh,
            (failed.state.mutation as AndroidAppConfigMutation.Failed).operation,
        )

        val retried = AndroidAppConfigReducer.reduce(failed.state, AndroidAppConfigEvent.RetryChange)
        val retryEffect = retried.effect as AndroidAppConfigEffect.Refresh
        assertTrue(retryEffect.requestId.value > requestId.value)
        assertEquals(VideoPlaybackType.Mp4, retried.state.readyPreferences().videoPlaybackType)
    }

    @Test
    fun laterChangeSupersedesOnlyRetryableMutationFailures() {
        val failure = AndroidAppConfigFailure.Unexpected(IllegalStateException("offline"))
        val replacement = AndroidAppConfigChange.AutoplayNextVideo(enabled = true)

        listOf(failedSaveState(failure), failedRefreshState(failure)).forEach { failed ->
            val superseded =
                AndroidAppConfigReducer.reduce(
                    failed,
                    AndroidAppConfigEvent.ChangeRequested(replacement),
                )

            assertTrue(superseded.consumed)
            assertEquals(replacement, (superseded.effect as AndroidAppConfigEffect.Save).change)
            assertTrue(superseded.state.readyPreferences().autoplayNextVideo)
        }

        val authenticationFailure =
            AndroidAppConfigFailure.AuthenticationRequired(
                PutioConfigurationException("invalid token"),
            )
        val failedAuth = failedSaveState(authenticationFailure)
        val rejected =
            AndroidAppConfigReducer.reduce(
                failedAuth,
                AndroidAppConfigEvent.ChangeRequested(replacement),
            )
        assertFalse(rejected.consumed)
        assertSame(failedAuth, rejected.state)
        assertSame(authenticationFailure, rejected.state.authoritativeSessionFailure())
    }

    @Test
    fun retriesOnlyRetryableFailedLoads() {
        val failure = AndroidAppConfigFailure.Unexpected(IllegalStateException("offline"))
        val failed = failedLoadState(failure)
        val retry = AndroidAppConfigReducer.reduce(failed, AndroidAppConfigEvent.RetryLoad)

        assertTrue(retry.state.content is AndroidAppConfigContent.Loading)
        assertTrue(retry.effect is AndroidAppConfigEffect.Load)

        val duplicate = AndroidAppConfigReducer.reduce(retry.state, AndroidAppConfigEvent.RetryLoad)
        assertFalse(duplicate.consumed)
        assertSame(retry.state, duplicate.state)

        val authenticationFailure =
            AndroidAppConfigFailure.AuthenticationRequired(
                PutioConfigurationException("invalid token"),
            )
        val failedAuth = failedLoadState(authenticationFailure)
        val rejected = AndroidAppConfigReducer.reduce(failedAuth, AndroidAppConfigEvent.RetryLoad)
        assertFalse(rejected.consumed)
        assertSame(failedAuth, rejected.state)
        assertSame(authenticationFailure, rejected.state.authoritativeSessionFailure())
    }

    @Test
    fun ignoresStaleAndWrongPhaseResults() {
        val start = AndroidAppConfigReducer.start()
        val initialRequestId = (start.effect as AndroidAppConfigEffect.Load).requestId
        val failure = AndroidAppConfigFailure.Unexpected(IllegalStateException("offline"))
        listOf(
            AndroidAppConfigEvent.LoadSucceeded(AndroidAppConfigRequestId(99L), Preferences),
            AndroidAppConfigEvent.LoadFailed(AndroidAppConfigRequestId(99L), failure),
        ).forEach { staleLoad ->
            val result = AndroidAppConfigReducer.reduce(start.state, staleLoad)
            assertFalse(result.consumed)
            assertSame(start.state, result.state)
        }

        val loaded =
            AndroidAppConfigReducer.reduce(
                start.state,
                AndroidAppConfigEvent.LoadSucceeded(initialRequestId, Preferences),
            ).state
        val saving =
            AndroidAppConfigReducer.reduce(
                loaded,
                AndroidAppConfigEvent.ChangeRequested(
                    AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4),
                ),
            )
        val requestId = (saving.effect as AndroidAppConfigEffect.Save).requestId
        listOf(
            AndroidAppConfigEvent.SaveSucceeded(AndroidAppConfigRequestId(99L)),
            AndroidAppConfigEvent.SaveFailed(AndroidAppConfigRequestId(99L), failure),
            AndroidAppConfigEvent.RefreshSucceeded(requestId, Preferences),
            AndroidAppConfigEvent.RefreshFailed(requestId, failure),
        ).forEach { staleOrWrongPhase ->
            val result = AndroidAppConfigReducer.reduce(saving.state, staleOrWrongPhase)
            assertFalse(result.consumed)
            assertSame(saving.state, result.state)
        }

        val refreshing =
            AndroidAppConfigReducer.reduce(
                saving.state,
                AndroidAppConfigEvent.SaveSucceeded(requestId),
            )
        listOf(
            AndroidAppConfigEvent.SaveSucceeded(requestId),
            AndroidAppConfigEvent.SaveFailed(requestId, failure),
            AndroidAppConfigEvent.RefreshSucceeded(AndroidAppConfigRequestId(99L), Preferences),
            AndroidAppConfigEvent.RefreshFailed(AndroidAppConfigRequestId(99L), failure),
        ).forEach { staleOrWrongPhase ->
            val result = AndroidAppConfigReducer.reduce(refreshing.state, staleOrWrongPhase)
            assertFalse(result.consumed)
            assertSame(refreshing.state, result.state)
        }
    }

    @Test
    fun onlyAuthenticationFailuresCrossTheAuthoritativeSessionBoundary() {
        val authenticationFailure =
            AndroidAppConfigFailure.AuthenticationRequired(
                PutioConfigurationException("invalid token"),
            )
        val retryableFailure = AndroidAppConfigFailure.Unexpected(IllegalStateException("offline"))

        assertSame(authenticationFailure, failedLoadState(authenticationFailure).authoritativeSessionFailure())
        assertSame(authenticationFailure, failedSaveState(authenticationFailure).authoritativeSessionFailure())
        assertNull(failedLoadState(retryableFailure).authoritativeSessionFailure())
        assertNull(failedSaveState(retryableFailure).authoritativeSessionFailure())
    }

    private fun requestChange(change: AndroidAppConfigChange): AndroidAppConfigTransition =
        AndroidAppConfigReducer.reduce(
            loadedState(Preferences),
            AndroidAppConfigEvent.ChangeRequested(change),
        )

    private fun failedLoadState(failure: AndroidAppConfigFailure): AndroidAppConfigState {
        val start = AndroidAppConfigReducer.start()
        val requestId = (start.effect as AndroidAppConfigEffect.Load).requestId
        return AndroidAppConfigReducer.reduce(
            start.state,
            AndroidAppConfigEvent.LoadFailed(requestId, failure),
        ).state
    }

    private fun failedSaveState(failure: AndroidAppConfigFailure): AndroidAppConfigState {
        val saving = requestChange(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4))
        val requestId = (saving.effect as AndroidAppConfigEffect.Save).requestId
        return AndroidAppConfigReducer.reduce(
            saving.state,
            AndroidAppConfigEvent.SaveFailed(requestId, failure),
        ).state
    }

    private fun failedRefreshState(failure: AndroidAppConfigFailure): AndroidAppConfigState {
        val saving = requestChange(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4))
        val requestId = (saving.effect as AndroidAppConfigEffect.Save).requestId
        val refreshing =
            AndroidAppConfigReducer.reduce(
                saving.state,
                AndroidAppConfigEvent.SaveSucceeded(requestId),
            )
        return AndroidAppConfigReducer.reduce(
            refreshing.state,
            AndroidAppConfigEvent.RefreshFailed(requestId, failure),
        ).state
    }

    private fun loadedState(preferences: AndroidAppConfigPreferences): AndroidAppConfigState {
        val start = AndroidAppConfigReducer.start()
        val requestId = (start.effect as AndroidAppConfigEffect.Load).requestId
        return AndroidAppConfigReducer.reduce(
            start.state,
            AndroidAppConfigEvent.LoadSucceeded(requestId, preferences),
        ).state
    }

    private fun AndroidAppConfigState.readyPreferences(): AndroidAppConfigPreferences =
        (content as AndroidAppConfigContent.Ready).preferences

    private companion object {
        val Preferences = AndroidAppConfigPreferences()
    }
}
