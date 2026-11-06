package io.putdotio.android.playback

import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, no API calls: plays a local file on the app's renderers, muted, and reports which decoder
 * took each track and whether the device inventory counts the file's codecs.
 *
 * Arguments: `putio.ffmpegAudio.enabled=true`; `putio.ffmpegAudio.file`, a file name in this test
 * package's external files directory; `putio.ffmpegAudio.audioMime`, the audio track to prefer;
 * `putio.ffmpegAudio.expect`, `decoded` (audio the platform lacks plays on FFmpeg, video on MediaCodec)
 * or `rejected` (no decoder takes the video, as for AV1 on a device without one).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class FfmpegAudioProofTest {
    private val arguments: Bundle = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theAppRenderersDecodeTheFileAsExpected() {
        assumeTrue("FFmpeg audio proof requires opt-in", arguments.getString("putio.ffmpegAudio.enabled") == "true")
        val file = File(context.getExternalFilesDir(null), requireNotNull(arguments.getString("putio.ffmpegAudio.file")))
        val audioMime = requireNotNull(arguments.getString("putio.ffmpegAudio.audioMime"))
        val expect = requireNotNull(arguments.getString("putio.ffmpegAudio.expect"))
        assertTrue("Missing ${file.path}", file.isFile)

        val run = PlayerRun(file, audioMime).also { it.play(PLAY_MILLIS) }
        val withFfmpeg = MediaCodecDecoderInventory(context)
        val platformOnly = MediaCodecDecoderInventory(context) { false }
        val audioFormat = Format.Builder().setSampleMimeType(audioMime).setChannelCount(6).setSampleRate(48_000).build()
        val videoFormat = run.videoFormat
        val result = JSONObject()
            .put("file", file.name)
            .put("audioDecoder", run.audioDecoder)
            .put("videoDecoder", run.videoDecoder)
            .put("audioMime", run.audioFormat?.sampleMimeType)
            .put("videoMime", videoFormat?.sampleMimeType)
            .put("videoSupported", run.videoSupported)
            .put("positionMs", run.positionMs)
            .put("droppedFrames", run.droppedFrames)
            .put("error", run.error?.errorCodeName)
            .put("inventoryAudio", withFfmpeg.plays(audioFormat))
            .put("platformAudio", platformOnly.plays(audioFormat))
            .put("inventoryVideo", videoFormat?.let(withFfmpeg::plays))
        Log.i(TAG, "RESULT $result")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("result", result.toString()) })

        assertNull(run.error)
        assertEquals(audioMime, run.audioFormat?.sampleMimeType)
        assertTrue(withFfmpeg.plays(audioFormat))
        assertTrue("Played ${run.positionMs} ms", run.positionMs >= PLAY_MILLIS - SLACK_MILLIS)
        when (expect) {
            "decoded" -> {
                assertFalse(platformOnly.plays(audioFormat))
                assertTrue("FFmpeg did not take the audio: ${run.audioDecoder}", run.audioDecoder.orEmpty().startsWith("ffmpeg"))
                assertNotNull(run.videoDecoder)
                assertFalse("Video left MediaCodec: ${run.videoDecoder}", run.videoDecoder.orEmpty().startsWith("ffmpeg"))
                assertTrue(run.videoSupported == true)
            }
            "rejected" -> {
                assertNull("A decoder took the video: ${run.videoDecoder}", run.videoDecoder)
                assertFalse(run.videoSupported ?: true)
                assertFalse(videoFormat?.let(withFfmpeg::plays) ?: true)
            }
            else -> error("Unknown expect $expect")
        }
    }

    private inner class PlayerRun(private val file: File, private val audioMime: String) {
        private val thread = HandlerThread("ffmpeg-audio-proof").apply { start() }
        private val handler = Handler(thread.looper)

        @Volatile var audioDecoder: String? = null
        @Volatile var videoDecoder: String? = null
        @Volatile var audioFormat: Format? = null
        @Volatile var videoFormat: Format? = null
        @Volatile var videoSupported: Boolean? = null
        @Volatile var error: PlaybackException? = null
        @Volatile var positionMs: Long = 0
        @Volatile var droppedFrames: Int = 0

        fun play(millis: Long) {
            val reader = ImageReader.newInstance(SURFACE_WIDTH, SURFACE_HEIGHT, ImageFormat.PRIVATE, SURFACE_IMAGES)
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
            val player = onPlayer {
                ExoPlayer.Builder(context, playbackRenderersFactory(context))
                    .setLooper(thread.looper)
                    .build()
                    .apply {
                        volume = 0f
                        setVideoSurface(reader.surface)
                        addAnalyticsListener(Listener(this))
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                        prepare()
                        play()
                    }
            }
            val deadline = System.currentTimeMillis() + millis + START_TIMEOUT_MILLIS
            while (System.currentTimeMillis() < deadline && error == null) {
                positionMs = onPlayer { player.currentPosition }
                if (positionMs >= millis) break
                Thread.sleep(POLL_MILLIS)
            }
            onPlayer { player.release() }
            reader.close()
            thread.quitSafely()
        }

        private fun <T> onPlayer(block: () -> T): T {
            var value: Result<T>? = null
            val done = CountDownLatch(1)
            handler.post {
                value = runCatching(block)
                done.countDown()
            }
            check(done.await(START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { "The player thread stalled" }
            return checkNotNull(value).getOrThrow()
        }

        private inner class Listener(private val player: ExoPlayer) : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                audioDecoder = decoderName
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                videoDecoder = decoderName
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                audioFormat = format
            }

            override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
                val audio = tracks.groups.firstOrNull {
                    it.type == C.TRACK_TYPE_AUDIO && it.getTrackFormat(0).sampleMimeType == audioMime
                }
                if (audio != null && !audio.isSelected) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(audio.mediaTrackGroup, 0))
                        .build()
                }
                val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
                videoFormat = video.getTrackFormat(0)
                videoSupported = video.isTrackSupported(0)
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                this@PlayerRun.droppedFrames += droppedFrames
            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                this@PlayerRun.error = error
            }
        }
    }

    private companion object {
        const val TAG = "FfmpegAudioProof"
        const val PLAY_MILLIS = 8_000L
        const val SLACK_MILLIS = 500L
        const val START_TIMEOUT_MILLIS = 30_000L
        const val POLL_MILLIS = 250L
        const val SURFACE_WIDTH = 3_840
        const val SURFACE_HEIGHT = 2_160
        const val SURFACE_IMAGES = 4
    }
}
