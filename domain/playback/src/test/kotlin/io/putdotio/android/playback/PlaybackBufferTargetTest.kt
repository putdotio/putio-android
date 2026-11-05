package io.putdotio.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferTargetTest {
    @Test
    fun bufferTargetGrowsWithTheHeapClass() {
        assertEquals(16 * MIB, bufferTargetBytes(memoryClassMb = 512, isLowRam = true))
        assertEquals(16 * MIB, bufferTargetBytes(memoryClassMb = 96, isLowRam = false))
        assertEquals(40 * MIB, bufferTargetBytes(memoryClassMb = 128, isLowRam = false))
        assertEquals(40 * MIB, bufferTargetBytes(memoryClassMb = 160, isLowRam = false))
        assertEquals(64 * MIB, bufferTargetBytes(memoryClassMb = 256, isLowRam = false))
        assertEquals(96 * MIB, bufferTargetBytes(memoryClassMb = 512, isLowRam = false))
    }

    private companion object {
        const val MIB = 1024 * 1024
    }
}
