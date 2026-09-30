package io.putdotio.android.tv.player

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TvPlayerTracksTest {
    @Test
    fun buttonsShowLanguageForSeveralAudioTracksSubtitlesForAnyAndSpeedAlways() {
        assertEquals(listOf(TvPlayerControl.Speed), tvOptionButtons(audioTracks = 1, subtitleTracks = 0))
        assertEquals(
            listOf(TvPlayerControl.Language, TvPlayerControl.Subtitles, TvPlayerControl.Speed),
            tvOptionButtons(audioTracks = 2, subtitleTracks = 1),
        )
    }

    @Test
    fun aTrackShowsItsNameThenItsLanguageThenItsNumber() {
        val labels = tvTrackLabels(
            tracks = listOf(TvTrackName("Italian (AC-3 5.1)", "it"), TvTrackName(null, "de"), TvTrackName(" ", null)),
            languageFirst = false,
            locale = Locale.US,
            numbered = listOf("Audio track 1", "Audio track 2", "Audio track 3"),
        )
        assertEquals(listOf("Italian (AC-3 5.1)", "German", "Audio track 3"), labels)
    }

    @Test
    fun mp4SubtitlesLeadWithTheirLanguageAsTheRnPlayerRelabelledThem() {
        val labels = tvTrackLabels(
            tracks = listOf(TvTrackName("Sintel.en.srt", "en"), TvTrackName(null, "tr"), TvTrackName(null, null)),
            languageFirst = true,
            locale = Locale.US,
            numbered = listOf("1", "2", "3"),
        )
        assertEquals(listOf("EN - Sintel.en.srt", "TR", "UNKNOWN"), labels)
    }

    @Test
    fun onlyRepeatedLabelsNeedDisambiguation() {
        assertEquals(setOf("English"), repeatedTrackLabels(listOf("English", "German", "English")))
    }
}
