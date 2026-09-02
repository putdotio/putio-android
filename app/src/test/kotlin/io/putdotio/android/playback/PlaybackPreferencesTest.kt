package io.putdotio.android.playback

import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigFailure
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigReducer
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PlaybackPreference
import org.junit.Assert.assertEquals
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

    private fun readyState(
        preferences: AndroidAppConfigPreferences = AndroidAppConfigPreferences(),
    ): AndroidAppConfigState =
        AndroidAppConfigState(
            content = AndroidAppConfigContent.Ready(preferences),
            mutation = AndroidAppConfigMutation.Idle,
            nextRequestValue = 2L,
        )
}
