package io.putdotio.android.playback

import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigFailure
import io.putdotio.android.settings.AndroidAppConfigEffect
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigReducer
import io.putdotio.android.settings.AndroidAppConfigRequestId
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PlaybackPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPreferencesTest {
    @Test
    fun appConfigMapsPlaybackTypeAndFallsBackToHlsUntilReady() {
        assertEquals(PlaybackPreference.HLS, readyState().playbackPreference())
        assertEquals(
            PlaybackPreference.MP4,
            readyState(
                AndroidAppConfigPreferences(videoPlaybackType = VideoPlaybackType.Mp4),
            ).playbackPreference(),
        )
        assertEquals(PlaybackPreference.HLS, AndroidAppConfigReducer.start().state.playbackPreference())
        assertEquals(
            PlaybackPreference.HLS,
            AndroidAppConfigState(
                content =
                    AndroidAppConfigContent.Failed(
                        AndroidAppConfigFailure.AccessDenied(PutioConfigurationException("forbidden")),
                    ),
                mutation = AndroidAppConfigMutation.Idle,
                nextRequestValue = 2L,
            ).playbackPreference(),
        )
    }

    @Test
    fun playbackKeepsConfirmedFormatThroughSaveAndRefreshFailures() {
        val saving = AndroidAppConfigReducer.reduce(
            readyState(),
            AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4)),
        )
        val requestId = requireNotNull(saving.effect).requestId
        val saveFailed = AndroidAppConfigReducer.reduce(
            saving.state,
            AndroidAppConfigEvent.SaveFailed(
                requestId,
                AndroidAppConfigFailure.AccessDenied(PutioConfigurationException("forbidden")),
            ),
        )
        val refreshing = AndroidAppConfigReducer.reduce(
            saving.state,
            AndroidAppConfigEvent.SaveSucceeded(requestId),
        )
        val refreshFailed = AndroidAppConfigReducer.reduce(
            refreshing.state,
            AndroidAppConfigEvent.RefreshFailed(
                requestId,
                AndroidAppConfigFailure.AccessDenied(PutioConfigurationException("forbidden")),
            ),
        )
        for (state in listOf(saving.state, saveFailed.state, refreshing.state, refreshFailed.state)) {
            assertEquals("mutation=${state.mutation}", PlaybackPreference.HLS, state.playbackPreference())
        }
        val refreshed = AndroidAppConfigReducer.reduce(
            refreshing.state,
            AndroidAppConfigEvent.RefreshSucceeded(
                requestId,
                AndroidAppConfigPreferences(videoPlaybackType = VideoPlaybackType.Mp4),
            ),
        )
        assertEquals(PlaybackPreference.MP4, refreshed.state.playbackPreference())
    }

    @Test
    fun anotherSettingSaveDoesNotConfirmAnUnrefreshedPlaybackFormat() {
        val saving = AndroidAppConfigReducer.reduce(
            readyState(AndroidAppConfigPreferences(videoPlaybackType = VideoPlaybackType.Mp4)),
            AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Hls)),
        )
        val requestId = requireNotNull(saving.effect).requestId
        val refreshing = AndroidAppConfigReducer.reduce(saving.state, AndroidAppConfigEvent.SaveSucceeded(requestId))
        val refreshFailed = AndroidAppConfigReducer.reduce(
            refreshing.state,
            AndroidAppConfigEvent.RefreshFailed(
                requestId,
                AndroidAppConfigFailure.AccessDenied(PutioConfigurationException("forbidden")),
            ),
        )
        val autoplaySaving = AndroidAppConfigReducer.reduce(
            refreshFailed.state,
            AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.AutoplayNextVideo(true)),
        )
        val autoplayRequestId = requireNotNull(autoplaySaving.effect).requestId
        val autoplaySaveFailed = AndroidAppConfigReducer.reduce(
            autoplaySaving.state,
            AndroidAppConfigEvent.SaveFailed(
                autoplayRequestId,
                AndroidAppConfigFailure.AccessDenied(PutioConfigurationException("forbidden")),
            ),
        )
        assertEquals(PlaybackPreference.MP4, autoplaySaving.state.playbackPreference())
        assertEquals(PlaybackPreference.MP4, autoplaySaveFailed.state.playbackPreference())
        val autoplayRefreshing = AndroidAppConfigReducer.reduce(
            autoplaySaving.state,
            AndroidAppConfigEvent.SaveSucceeded(autoplayRequestId),
        )
        val refreshed = AndroidAppConfigReducer.reduce(
            autoplayRefreshing.state,
            AndroidAppConfigEvent.RefreshSucceeded(
                autoplayRequestId,
                AndroidAppConfigPreferences(videoPlaybackType = VideoPlaybackType.Hls, autoplayNextVideo = true),
            ),
        )
        assertEquals(PlaybackPreference.HLS, refreshed.state.playbackPreference())
    }

    @Test
    fun autoplayUsesOnlyTheLastConfirmedPreference() {
        val enabled = AndroidAppConfigPreferences(autoplayNextVideo = true)
        val disabled = AndroidAppConfigPreferences(autoplayNextVideo = false)
        val requestId = AndroidAppConfigRequestId(2L)

        assertTrue(readyState(enabled).confirmedAutoplayNextVideo())
        assertFalse(AndroidAppConfigReducer.start().state.confirmedAutoplayNextVideo())
        assertFalse(
            readyState(
                preferences = enabled,
                mutation =
                    AndroidAppConfigMutation.Saving(
                        requestId = requestId,
                        change = AndroidAppConfigChange.AutoplayNextVideo(true),
                        previousPreferences = disabled,
                        operation = AndroidAppConfigMutation.Operation.Save,
                    ),
                confirmedPreferences = disabled,
            ).confirmedAutoplayNextVideo(),
        )
        assertTrue(
            readyState(
                preferences = disabled,
                mutation =
                    AndroidAppConfigMutation.Failed(
                        change = AndroidAppConfigChange.AutoplayNextVideo(false),
                        failure = AndroidAppConfigFailure.Unexpected(IllegalStateException("refresh failed")),
                        previousPreferences = enabled,
                        operation = AndroidAppConfigMutation.Operation.Refresh,
                    ),
                confirmedPreferences = enabled,
            ).confirmedAutoplayNextVideo(),
        )
    }

    @Test
    fun chainedMutationsDoNotPromoteAnUnconfirmedAutoplayValue() {
        val start = AndroidAppConfigReducer.start()
        val loadRequestId = (start.effect as AndroidAppConfigEffect.Load).requestId
        val loaded =
            AndroidAppConfigReducer.reduce(
                start.state,
                AndroidAppConfigEvent.LoadSucceeded(
                    loadRequestId,
                    AndroidAppConfigPreferences(autoplayNextVideo = false),
                ),
            )
        val savingAutoplay =
            AndroidAppConfigReducer.reduce(
                loaded.state,
                AndroidAppConfigEvent.ChangeRequested(
                    AndroidAppConfigChange.AutoplayNextVideo(true),
                ),
            )
        val autoplayRequestId =
            (savingAutoplay.effect as AndroidAppConfigEffect.Save).requestId
        val refreshingAutoplay =
            AndroidAppConfigReducer.reduce(
                savingAutoplay.state,
                AndroidAppConfigEvent.SaveSucceeded(autoplayRequestId),
            )
        val refreshFailed =
            AndroidAppConfigReducer.reduce(
                refreshingAutoplay.state,
                AndroidAppConfigEvent.RefreshFailed(
                    autoplayRequestId,
                    AndroidAppConfigFailure.Unexpected(IllegalStateException("refresh failed")),
                ),
            )
        val savingPlaybackType =
            AndroidAppConfigReducer.reduce(
                refreshFailed.state,
                AndroidAppConfigEvent.ChangeRequested(
                    AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4),
                ),
            )
        val playbackTypeRequestId =
            (savingPlaybackType.effect as AndroidAppConfigEffect.Save).requestId
        val saveFailed =
            AndroidAppConfigReducer.reduce(
                savingPlaybackType.state,
                AndroidAppConfigEvent.SaveFailed(
                    playbackTypeRequestId,
                    AndroidAppConfigFailure.Unexpected(IllegalStateException("save failed")),
                ),
            )

        assertFalse(refreshFailed.state.confirmedAutoplayNextVideo())
        assertFalse(savingPlaybackType.state.confirmedAutoplayNextVideo())
        assertFalse(saveFailed.state.confirmedAutoplayNextVideo())
        assertTrue((saveFailed.state.content as AndroidAppConfigContent.Ready).preferences.autoplayNextVideo)
    }

    private fun readyState(
        preferences: AndroidAppConfigPreferences = AndroidAppConfigPreferences(),
        mutation: AndroidAppConfigMutation = AndroidAppConfigMutation.Idle,
        confirmedPreferences: AndroidAppConfigPreferences = preferences,
    ): AndroidAppConfigState {
        val loading = AndroidAppConfigReducer.start()
        return AndroidAppConfigReducer.reduce(
            loading.state,
            AndroidAppConfigEvent.LoadSucceeded(requireNotNull(loading.effect).requestId, preferences),
        ).state.copy(
            mutation = mutation,
            confirmedPreferences = confirmedPreferences,
        )
    }
}
