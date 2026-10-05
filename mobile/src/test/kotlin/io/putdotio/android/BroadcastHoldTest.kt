package io.putdotio.android

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A receiver's broadcast ends within the 10 s a foreground-queue delivery gets, exactly once. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BroadcastHoldTest {
    private var finished = 0

    @Test
    fun workStillRunningLetsTheBroadcastEndAfterEightSeconds() {
        val hold = BroadcastHold({ finished++ })

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BROADCAST_HOLD_MILLIS - 1))
        assertEquals(0, finished)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, finished)

        // The work finishing later ends nothing twice.
        hold.release()
        assertEquals(1, finished)
    }

    @Test
    fun workThatFinishesFirstEndsTheBroadcastThenAndOnlyThen() {
        val hold = BroadcastHold({ finished++ })

        hold.release()
        assertEquals(1, finished)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BROADCAST_HOLD_MILLIS * 2))
        assertEquals(1, finished)
    }
}
