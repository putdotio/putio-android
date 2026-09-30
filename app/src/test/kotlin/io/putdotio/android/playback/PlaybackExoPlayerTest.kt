package io.putdotio.android.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackExoPlayerTest {
    @Test
    fun emulatorCodecsDemoteGoldfishDecoders() {
        assertTrue(requiresEmulatorCodecWorkaround(37, "ranchu"))
        assertTrue(requiresEmulatorCodecWorkaround(37, "goldfish"))
        assertFalse(requiresEmulatorCodecWorkaround(36, "ranchu"))
        assertFalse(requiresEmulatorCodecWorkaround(37, "tensor"))
        assertEquals(1, emulatorCodecPriority("c2.goldfish.h264.decoder"))
        assertEquals(0, emulatorCodecPriority("c2.android.avc.decoder"))
        assertEquals(SURFACE_TYPE_TEXTURE_VIEW, playbackSurfaceType(37, "ranchu"))
        assertEquals(SURFACE_TYPE_SURFACE_VIEW, playbackSurfaceType(36, "ranchu"))
        assertEquals(SURFACE_TYPE_SURFACE_VIEW, playbackSurfaceType(37, "tensor"))
    }
}
