package io.putdotio.android.playback

import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackUnattendedStartTest {
    @Test
    fun aVideoNoOneIsAskedAboutResumesWherePutioSavedIt() {
        assertEquals(754_000L, ready(savedSeconds = 754.0).unattendedStartMillis(durationSeconds = 2_400.0))
    }

    @Test
    fun anAccountThatStartsOverStartsFromTheBeginning() {
        assertEquals(0L, ready(savedSeconds = 754.0, useStartFrom = false).unattendedStartMillis(2_400.0))
    }

    @Test
    fun aVideoSavedWithinItsLastSecondsStartsOverAsAFinishedOne() {
        assertEquals(0L, ready(savedSeconds = 2_395.0).unattendedStartMillis(durationSeconds = 2_400.0))
        assertEquals(0L, ready(savedSeconds = 2_395.0, durationSeconds = 2_400.0).unattendedStartMillis(null))
    }

    @Test
    fun aFiftyMinuteVideoIsFinishedFrom47m30s() {
        assertEquals(2_849_000L, ready(savedSeconds = 2_849.0).unattendedStartMillis(durationSeconds = 3_000.0))
        assertEquals(0L, ready(savedSeconds = 2_850.0).unattendedStartMillis(durationSeconds = 3_000.0))
    }

    private fun ready(
        savedSeconds: Double,
        useStartFrom: Boolean = true,
        durationSeconds: Double? = null,
    ) = PlaybackResolution.Ready(
        source = PlaybackSource(
            fileId = 7L,
            kind = PlaybackSourceKind.MP4,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/7/mp4/download"),
            startFromSeconds = savedSeconds,
            subtitles = PlaybackSubtitles.None,
        ),
        useStartFrom = useStartFrom,
        durationSeconds = durationSeconds,
    )
}
