package io.putdotio.android.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackFfmpegAudioTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun ffmpegDecodesAudioOnlyAfterMediaCodec() {
        val renderers = playbackRenderersFactory(context).createRenderers(
            Handler(Looper.getMainLooper()),
            object : VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            { },
            { },
        )

        val audio = renderers.filter { it.trackType == C.TRACK_TYPE_AUDIO }
        val video = renderers.filter { it.trackType == C.TRACK_TYPE_VIDEO }
        assertEquals(2, audio.size)
        assertTrue(audio[0] is MediaCodecAudioRenderer)
        assertTrue(audio[1] is FfmpegAudioRenderer)
        assertTrue(video.isNotEmpty())
        assertTrue(video.all { it is MediaCodecVideoRenderer })
    }

    @Test
    fun theInventoryCountsFfmpegForAudioButNeverForVideo() {
        val withFfmpeg = MediaCodecDecoderInventory(context) { true }
        val withoutFfmpeg = MediaCodecDecoderInventory(context) { false }
        val eac3 = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setChannelCount(6)
            .setSampleRate(48_000)
            .build()
        val av1 = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setWidth(3_840)
            .setHeight(2_160)
            .build()

        assertTrue(withFfmpeg.plays(eac3))
        assertFalse(withoutFfmpeg.plays(eac3))
        assertFalse(withFfmpeg.plays(av1))
    }
}
