package io.putdotio.android.design

import org.junit.Assert.assertEquals
import org.junit.Test

class PutioWelcomeSequenceTest {
    @Test
    fun sequenceHoldsEachFrameThenRollsToTheNext() {
        assertEquals(0f, welcomeSequencePosition(0L, frameCount = 4))
        assertEquals(0f, welcomeSequencePosition(1_900L, frameCount = 4))
        // The roll eases on cubic-bezier(0.7, 0, 0.2, 1): slow out, fast through the middle.
        assertEquals(0.066f, welcomeSequencePosition(2_050L, frameCount = 4), EASING_TOLERANCE)
        assertEquals(0.636f, welcomeSequencePosition(2_200L, frameCount = 4), EASING_TOLERANCE)
        assertEquals(0.954f, welcomeSequencePosition(2_350L, frameCount = 4), EASING_TOLERANCE)
        assertEquals(1f, welcomeSequencePosition(2_500L, frameCount = 4))
        assertEquals(3f, welcomeSequencePosition(7_500L, frameCount = 4))
        // The last roll brings the first frame back in.
        assertEquals(3.636f, welcomeSequencePosition(9_700L, frameCount = 4), EASING_TOLERANCE)
        assertEquals(0f, welcomeSequencePosition(10_000L, frameCount = 4))
    }
}

private const val EASING_TOLERANCE = 0.005f
