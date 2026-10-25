package io.putdotio.android.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
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
    fun theTextRendererReadsItsCuesAheadByTheLead() {
        val text = RecordingRenderer()
        val renderer = SubtitleLeadRenderer(text)

        renderer.render(10_000_000L, 0L)
        renderer.handleMessage(SubtitleLeadRenderer.MSG_SET_SUBTITLE_LEAD, 1_300_000L)
        renderer.render(10_000_000L, 0L)

        assertEquals(listOf(10_000_000L, 11_300_000L), text.positions)
    }

    @Test
    fun delayedCuesShowWhatPlayedThatLongAgoAndForgetASeek() {
        val first = listOf(Cue.Builder().setText("first").build())
        val second = listOf(Cue.Builder().setText("second").build())
        val cues = DelayedSubtitleCues()
        cues.record(10_000L, first)
        cues.record(13_000L, second)
        cues.record(16_000L, emptyList())

        assertEquals(emptyList<Cue>(), cues.at(10_400L, delayMillis = 500L))
        assertEquals(first, cues.at(10_500L, delayMillis = 500L))
        assertEquals(second, cues.at(16_400L, delayMillis = 3_000L))
        assertEquals(emptyList<Cue>(), cues.at(19_000L, delayMillis = 3_000L))
        // A seek back replaces what came after it.
        cues.record(12_000L, second)
        assertEquals(second, cues.at(15_000L, delayMillis = 3_000L))
        cues.clear()
        assertEquals(emptyList<Cue>(), cues.at(20_000L, delayMillis = 500L))
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
        assertTrue(text.all { it is SubtitleLeadRenderer })
        assertTrue(renderers.filter { it.trackType != C.TRACK_TYPE_TEXT }.none { it is SubtitleLeadRenderer })
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
