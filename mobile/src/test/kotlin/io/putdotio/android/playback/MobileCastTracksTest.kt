package io.putdotio.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileCastTracksTest {
    @Test
    fun tracksShowTheirNameElseTheirLanguageNumberedWhereTwoMatch() {
        val labels = castTrackLabels(
            listOf(
                CastTrack(1L, name = null, language = "en"),
                CastTrack(2L, name = null, language = "ja"),
                CastTrack(3L, name = "Commentary", language = "en"),
                CastTrack(4L, name = null, language = "en"),
                CastTrack(5L, name = null, language = null),
            ),
            fallback = "Audio",
        )

        assertEquals(listOf("English 1", "Japanese", "Commentary", "English 2", "Audio"), labels)
    }

    @Test
    fun aChoiceReplacesOnlyTheTrackOfItsKind() {
        val status = CastRemoteStatus(
            fileId = 42L,
            state = CastRemoteState.Playing,
            positionMillis = 0L,
            durationMillis = null,
            sampledAtMillis = 0L,
            audioTracks = listOf(CastTrack(1L, null, "en"), CastTrack(2L, null, "ja")),
            textTracks = listOf(CastTrack(3L, null, "en"), CastTrack(4L, null, "tr")),
            activeTrackIds = setOf(1L, 3L),
        )

        assertEquals(setOf(2L, 3L), status.withAudio(2L))
        assertEquals(setOf(1L, 4L), status.withSubtitle(4L))
        assertEquals(setOf(1L), status.withSubtitle(null))
    }
}
