package io.putdotio.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackNoticesTest {
    @Test
    fun theFfmpegDecoderNoticesFollowTheCatalog() {
        assertEquals(
            "Set playbackNotices to the FFmpeg media3-ffmpeg-decoder ${BuildConfig.FFMPEG_DECODER_VERSION} pins",
            BuildConfig.FFMPEG_DECODER_VERSION,
            playbackNotices.single { it.name == "media3-ffmpeg-decoder" }.version,
        )
    }

    @Test
    fun theAssMediaNoticesFollowTheCatalog() {
        assertEquals(
            "Set playbackNotices to the libass-cmake submodules ass-media ${BuildConfig.ASS_MEDIA_VERSION} pins",
            BuildConfig.ASS_MEDIA_VERSION,
            playbackNotices.single { it.name == "ass-media" }.version,
        )
    }
}
