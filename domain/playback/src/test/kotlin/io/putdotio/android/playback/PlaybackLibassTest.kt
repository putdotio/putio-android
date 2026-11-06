package io.putdotio.android.playback

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.render.AssRenderer
import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.media.type.AssRenderType
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun thePlayerAddsTheLibassRendererOnlyWhenAskedAndKeepsTheSubtitleLead() {
        val withLibass = playbackExoPlayer(context, LibassSubtitles(cacheMegabytes = 32))
        val withoutLibass = playbackExoPlayer(context, libass = null)

        assertTrue(withLibass.usesLibass())
        assertTrue((0 until withLibass.rendererCount).any { withLibass.getRenderer(it) is SubtitleLeadRenderer })
        assertFalse(withoutLibass.usesLibass())
        withLibass.release()
        withoutLibass.release()
    }

    @Test
    fun sidecarAssKeepsMedia3TextCues() {
        val factory = MatroskaAssSubtitleParserFactory(AssHandler(AssRenderType.CUES)) { true }
        val sidecar = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_SSA)
            .setContainerMimeType(MimeTypes.TEXT_SSA)
            .build()
        val cues = mutableListOf<CuesWithTiming>()

        factory.create(sidecar).parse(SIDECAR.toByteArray(), SubtitleParser.OutputOptions.allCues(), cues::add)

        val cue = cues.flatMap { it.cues }.single()
        assertEquals("Sidecar line", cue.text.toString())
        assertNull(cue.bitmap)
    }

    @Test
    fun aSubtitleSizeTheViewerPickedKeepsEmbeddedAssAsMedia3TextCues() {
        SubtitleAppearanceStore(context).update(SubtitleTextSize.Large)
        val factory = MatroskaAssSubtitleParserFactory(AssHandler(AssRenderType.CUES), context::drawsAssWithLibass)
        val embedded = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_SSA)
            .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
            .setInitializationData(listOf(EMBEDDED_FORMAT.toByteArray(), EMBEDDED_HEADER.toByteArray()))
            .build()
        val cues = mutableListOf<CuesWithTiming>()

        factory.create(embedded).parse(EMBEDDED_SAMPLE.toByteArray(), SubtitleParser.OutputOptions.allCues(), cues::add)

        val cue = cues.flatMap { it.cues }.single()
        assertEquals("Embedded line", cue.text.toString())
        assertNull(cue.bitmap)
    }

    private fun ExoPlayer.usesLibass(): Boolean = (0 until rendererCount).any { getRenderer(it) is AssRenderer }

    private companion object {
        const val EMBEDDED_FORMAT =
            "Format: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
        const val EMBEDDED_SAMPLE = "Dialogue: 0:00:01:00,0:00:02:00,1,0,Default,,0,0,0,,Embedded line"
        val EMBEDDED_HEADER = """
            [Script Info]
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, Alignment
            Style: Default,Arial,40,&H00FFFFFF,2
        """.trimIndent()
        val SIDECAR = """
            [Script Info]
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, Alignment
            Style: Default,Arial,40,&H00FFFFFF,2

            [Events]
            Format: Layer, Start, End, Style, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,Sidecar line
        """.trimIndent()
    }
}
