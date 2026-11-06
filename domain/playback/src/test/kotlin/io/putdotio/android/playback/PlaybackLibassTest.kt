package io.putdotio.android.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackLibassTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun libassRendersOnlyOn64BitDevicesWithALargeHeap() {
        assertTrue(rendersAssWithLibass(is64Bit = true, isLowRam = false, memoryClassMb = 256))
        assertTrue(rendersAssWithLibass(is64Bit = true, isLowRam = false, memoryClassMb = 512))
        assertFalse(rendersAssWithLibass(is64Bit = false, isLowRam = false, memoryClassMb = 512))
        assertFalse(rendersAssWithLibass(is64Bit = true, isLowRam = true, memoryClassMb = 512))
        assertFalse(rendersAssWithLibass(is64Bit = true, isLowRam = false, memoryClassMb = 192))
    }

    @Test
    fun audioNeverUsesLibass() {
        assertTrue(libassSubtitles(context, PlaybackMediaType.AUDIO, cacheMegabytes = 32) == null)
    }

    @Test
    fun thePlayerAddsTheLibassRendererOnlyWhenAsked() {
        val withLibass = playbackExoPlayer(context, LibassSubtitles(cacheMegabytes = 32))
        val withoutLibass = playbackExoPlayer(context, libass = null)

        assertTrue(withLibass.usesLibass())
        assertFalse(withoutLibass.usesLibass())
        withLibass.release()
        withoutLibass.release()
    }
}
