package io.putdotio.android

import androidx.media3.common.Player as Media3Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePlaybackServiceTest {
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
