package io.putdotio.android

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileOAuthBrowserReturnTrackerTest {
    @Test
    fun `pause and resume while awaiting means the browser returned without a callback`() {
        val tracker = MobileOAuthBrowserReturnTracker()

        assertFalse(tracker.onLifecycleEvent(Lifecycle.Event.ON_RESUME, awaitingCallback = true, callbackDispatchSequence = 0))
        assertFalse(tracker.onLifecycleEvent(Lifecycle.Event.ON_PAUSE, awaitingCallback = true, callbackDispatchSequence = 0))
        assertTrue(tracker.onLifecycleEvent(Lifecycle.Event.ON_RESUME, awaitingCallback = true, callbackDispatchSequence = 0))
    }

    @Test
    fun `completed callback is not cancelled when the activity resumes`() {
        val tracker = MobileOAuthBrowserReturnTracker()

        assertFalse(tracker.onLifecycleEvent(Lifecycle.Event.ON_PAUSE, awaitingCallback = true, callbackDispatchSequence = 0))
        assertFalse(tracker.onLifecycleEvent(Lifecycle.Event.ON_RESUME, awaitingCallback = false, callbackDispatchSequence = 1))
    }

    @Test
    fun `saved departure survives activity recreation`() {
        val firstActivity = MobileOAuthBrowserReturnTracker()
        firstActivity.onLifecycleEvent(
            Lifecycle.Event.ON_PAUSE,
            awaitingCallback = true,
            callbackDispatchSequence = 4,
        )

        val recreatedActivity = MobileOAuthBrowserReturnTracker(
            initialLeftWhileAwaitingCallback = firstActivity.leftWhileAwaitingCallback,
            initialCallbackSequenceWhenPaused = firstActivity.callbackSequenceWhenPaused,
        )

        assertTrue(
            recreatedActivity.onLifecycleEvent(
                Lifecycle.Event.ON_RESUME,
                awaitingCallback = true,
                callbackDispatchSequence = 4,
            ),
        )
        assertFalse(recreatedActivity.leftWhileAwaitingCallback)
    }

    @Test
    fun `received callback is not reclassified as browser cancellation`() {
        val tracker = MobileOAuthBrowserReturnTracker()
        tracker.onLifecycleEvent(
            Lifecycle.Event.ON_PAUSE,
            awaitingCallback = true,
            callbackDispatchSequence = 8,
        )

        assertFalse(
            tracker.onLifecycleEvent(
                Lifecycle.Event.ON_RESUME,
                awaitingCallback = true,
                callbackDispatchSequence = 9,
            ),
        )
    }
}
