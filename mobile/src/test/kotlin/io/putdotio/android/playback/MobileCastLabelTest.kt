package io.putdotio.android.playback

import io.putdotio.android.R
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileCastLabelTest {
    @Test
    fun theReceiverLoadsUntilItFirstPlaysThenPlaysOrFails() {
        assertEquals(R.string.mobile_cast_loading_on, castLabel(failed = false, state = null, played = false))
        assertEquals(
            R.string.mobile_cast_loading_on,
            castLabel(failed = false, state = CastRemoteState.Buffering, played = false),
        )
        assertEquals(
            R.string.mobile_cast_playing_on,
            castLabel(failed = false, state = CastRemoteState.Playing, played = false),
        )
        assertEquals(
            "A receiver buffering after it played still plays the file",
            R.string.mobile_cast_playing_on,
            castLabel(failed = false, state = CastRemoteState.Buffering, played = true),
        )
        assertEquals(
            R.string.mobile_cast_failed,
            castLabel(failed = true, state = CastRemoteState.Buffering, played = false),
        )
    }
}
