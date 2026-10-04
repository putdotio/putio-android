package io.putdotio.android.playback

import androidx.media3.common.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSubtitleRenderingTest {
    @Test
    fun subtitleFrameMatchesTheFittedVideoSurface() {
        assertEquals(16f / 9f, VideoSize(1_920, 1_080).displayAspectRatioOrNull())
        assertEquals(FittedVideoSize(1_080, 608), fitInside(1_080, 2_160, 16f / 9f))
        assertEquals(FittedVideoSize(1_080, 1_920), fitInside(1_080, 2_160, 9f / 16f))
    }
}
