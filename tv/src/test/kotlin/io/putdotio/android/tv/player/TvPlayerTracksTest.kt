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
}
