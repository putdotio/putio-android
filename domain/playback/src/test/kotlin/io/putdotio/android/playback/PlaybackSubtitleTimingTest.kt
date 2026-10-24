package io.putdotio.android.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackSubtitleTimingTest {
    @Test
    fun theTextRendererReadsItsCuesAtTheOffsetPosition() {
        val text = RecordingRenderer()
        val renderer = SubtitleOffsetRenderer(text)

        renderer.render(10_000_000L, 0L)
        renderer.handleMessage(SubtitleOffsetRenderer.MSG_SET_SUBTITLE_OFFSET, 500_000L)
        renderer.render(10_000_000L, 0L)
        renderer.handleMessage(SubtitleOffsetRenderer.MSG_SET_SUBTITLE_OFFSET, -1_300_000L)
        renderer.render(10_000_000L, 0L)

        // Later subtitles read behind the position; sooner ones read ahead of it.
        assertEquals(listOf(10_000_000L, 9_500_000L, 11_300_000L), text.positions)
    }

    @Test
    fun onlyTextRenderersTakeTheOffset() {
        val renderers = SubtitleTimingRenderersFactory(ApplicationProvider.getApplicationContext())
            .createRenderers(
                Handler(Looper.getMainLooper()),
                object : VideoRendererEventListener {},
                object : AudioRendererEventListener {},
                { },
                { },
            )

        val text = renderers.filter { it.trackType == C.TRACK_TYPE_TEXT }
        assertTrue(text.isNotEmpty())
        assertTrue(text.all { it is SubtitleOffsetRenderer })
        assertTrue(renderers.filter { it.trackType != C.TRACK_TYPE_TEXT }.none { it is SubtitleOffsetRenderer })
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class RecordingRenderer : BaseRenderer(C.TRACK_TYPE_TEXT) {
    val positions = mutableListOf<Long>()

    override fun getName(): String = "recording"

    override fun supportsFormat(format: Format): Int = RendererCapabilities.create(C.FORMAT_HANDLED)

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        positions += positionUs
    }

    override fun isReady(): Boolean = true

    override fun isEnded(): Boolean = false
}
