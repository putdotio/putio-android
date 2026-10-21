package io.putdotio.android.playback

import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.PutioFailure
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigReducer
import io.putdotio.android.settings.AndroidAppConfigRequestId
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.android.settings.androidAppConfigState
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PlaybackPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPreferencesTest {
    @Test
    fun appConfigMapsConfirmedPreferencesAndFallsBackUntilReady() {
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
            androidAppConfigState(
                content = AndroidAppConfigContent.Failed(Forbidden),
                mutation = AndroidAppConfigMutation.Idle,
                nextRequestValue = 2L,
            ).playbackPreference(),
        )

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
                        failure = PutioFailure.Unexpected(IllegalStateException("refresh failed")),
                        previousPreferences = enabled,
                        operation = AndroidAppConfigMutation.Operation.Refresh,
                    ),
                confirmedPreferences = enabled,
            ).confirmedAutoplayNextVideo(),
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
            AndroidAppConfigEvent.SaveFailed(requestId, Forbidden),
        )
        val refreshing = AndroidAppConfigReducer.reduce(
            saving.state,
            AndroidAppConfigEvent.SaveSucceeded(requestId),
        )
        val refreshFailed = AndroidAppConfigReducer.reduce(
            refreshing.state,
            AndroidAppConfigEvent.RefreshFailed(requestId, Forbidden),
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

    private fun readyState(
        preferences: AndroidAppConfigPreferences = AndroidAppConfigPreferences(),
        mutation: AndroidAppConfigMutation = AndroidAppConfigMutation.Idle,
        confirmedPreferences: AndroidAppConfigPreferences = preferences,
    ): AndroidAppConfigState =
        androidAppConfigState(
            AndroidAppConfigContent.Ready(preferences),
            mutation,
            nextRequestValue = 2L,
            confirmedPreferences,
        )

    private companion object {
        val Forbidden = PutioFailure.AccessDenied(PutioConfigurationException("forbidden"))
    }
}
