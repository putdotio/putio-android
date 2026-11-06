package io.putdotio.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackNoticesTest {
    @Test
    fun theNextlibNoticesFollowTheCatalog() {
        assertEquals(
            "Set playbackNotices to the FFmpeg, dav1d and Mbed TLS versions nextlib ${BuildConfig.NEXTLIB_VERSION} pins",
            BuildConfig.NEXTLIB_VERSION,
            playbackNotices.single { it.name == "nextlib" }.version,
        )
    }
}
