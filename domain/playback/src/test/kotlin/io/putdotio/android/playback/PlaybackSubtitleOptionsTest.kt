package io.putdotio.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSubtitleOptionsTest {
    @Test
    fun theOffsetReadsInSecondsWithARealMinus() {
        assertEquals("0.0 s", formatSubtitleOffset(0L))
        assertEquals("−1.3 s", formatSubtitleOffset(-1_300L))
        assertEquals("+10.0 s", formatSubtitleOffset(10_000L))
    }
}
