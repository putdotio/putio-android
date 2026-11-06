package io.putdotio.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackNoticesTest {
    @Test
    fun theNextlibNoticesFollowTheCatalog() {
        assertEquals(
            "Set playbackNotices to the FFmpeg, dav1d and Mbed TLS nextlib ${BuildConfig.NEXTLIB_VERSION} pins",
            BuildConfig.NEXTLIB_VERSION,
            playbackNotices.single { it.name == "nextlib" }.version,
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
