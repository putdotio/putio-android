package io.putdotio.android.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TvPlayerTracksTest {
    @Test
    fun theChipsFollowThePhonesRules() {
        assertEquals(
            listOf(
                TvPlayerControl.Audio,
                TvPlayerControl.Subtitles,
                TvPlayerControl.Speed,
                TvPlayerControl.NextEpisode,
            ),
            tvPlayerChips(audioTracks = 1, subtitlesHidden = false, nextEpisode = true),
        )
        assertEquals(
            listOf(TvPlayerControl.Speed),
            tvPlayerChips(audioTracks = 0, subtitlesHidden = true, nextEpisode = false),
        )
    }

    @Test
    fun aStreamedOriginalVideoOffersTheStreamChipBeforeNextEpisode() {
        assertEquals(
            listOf(
                TvPlayerControl.Audio,
                TvPlayerControl.Subtitles,
                TvPlayerControl.Speed,
                TvPlayerControl.Stream,
                TvPlayerControl.NextEpisode,
            ),
            tvPlayerChips(audioTracks = 1, subtitlesHidden = false, nextEpisode = true, original = true),
        )
        assertEquals(
            listOf(TvPlayerControl.Speed),
            tvPlayerChips(
                audioTracks = 1,
                subtitlesHidden = false,
                nextEpisode = false,
                audioOnly = true,
                original = true,
            ),
        )
        assertEquals(
            TvPlayerSheet.Stream,
            TvPlayerOverlay().openSheet(TvPlayerControl.Stream).overlay.sheet,
        )
    }

    @Test
    fun audioOffersSpeedAndTheAudioChipOnlyWithTracksToChoose() {
        assertEquals(
            listOf(TvPlayerControl.Speed),
            tvPlayerChips(audioTracks = 1, subtitlesHidden = false, nextEpisode = false, audioOnly = true),
        )
        assertEquals(
            listOf(TvPlayerControl.Audio, TvPlayerControl.Speed),
            tvPlayerChips(audioTracks = 2, subtitlesHidden = false, nextEpisode = false, audioOnly = true),
        )
    }
}
