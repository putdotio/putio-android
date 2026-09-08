package io.putdotio.android

import androidx.media3.common.Player as Media3Player
import io.putdotio.android.auth.MobileAuthSessionId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePlaybackServiceTest {
    @Test
    fun leavingASessionStopsPlaybackButAColdStartDoesNot() {
        val a = MobileAuthSessionId(1L)
        val b = MobileAuthSessionId(2L)
        assertFalse(playbackSessionLeft(previous = null, current = null))
        assertFalse(playbackSessionLeft(previous = null, current = a))
        assertFalse(playbackSessionLeft(previous = a, current = a))
        assertTrue(playbackSessionLeft(previous = a, current = null))
        assertTrue(playbackSessionLeft(previous = a, current = b))
    }

    @Test
    fun swipingTheTaskAwayOnlyEndsInactiveAudio() {
        assertFalse(playbackStopsWithTask(playWhenReady = true, Media3Player.STATE_READY, mediaItemCount = 1))
        assertFalse(playbackStopsWithTask(playWhenReady = true, Media3Player.STATE_BUFFERING, mediaItemCount = 1))
        assertTrue(playbackStopsWithTask(playWhenReady = false, Media3Player.STATE_READY, mediaItemCount = 1))
        assertTrue(playbackStopsWithTask(playWhenReady = true, Media3Player.STATE_ENDED, mediaItemCount = 1))
        assertTrue(playbackStopsWithTask(playWhenReady = true, Media3Player.STATE_IDLE, mediaItemCount = 1))
        assertTrue(playbackStopsWithTask(playWhenReady = true, Media3Player.STATE_READY, mediaItemCount = 0))
    }
}
