package io.putdotio.android.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.peerless2012.ass.media.render.AssRenderer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class LibassProofTest {
    private val arguments: Bundle = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theSharedPlayerDrawsAssCuesAsTheDeviceTierChooses() {
        assumeTrue("libass proof requires opt-in", arguments.getString("putio.libass.enabled") == "true")
        val file = File(context.getExternalFilesDir(null), requireNotNull(arguments.getString("putio.libass.file")))
        val expect = requireNotNull(arguments.getString("putio.libass.expect"))
        val runId = requireNotNull(arguments.getString("putio.libass.runId"))
        assertTrue("Missing ${file.path}", file.isFile)
        val libass = when (val mode = arguments.getString("putio.libass.mode")) {
            "tier" -> libassSubtitles(context, PlaybackMediaType.VIDEO, PROOF_CACHE_MB)
            "on" -> LibassSubtitles(PROOF_CACHE_MB)
            "off" -> null
            else -> error("Unknown mode $mode")
        }

        val run = PlayerRun(file, libass).also { it.play() }
        val png = File(context.getExternalFilesDir(null), "libass-proof-$runId.png")
        run.drawCues(png)
        val result = JSONObject()
            .put("file", file.name)
            .put("mode", arguments.getString("putio.libass.mode"))
            .put("usesLibass", run.libassActive)
            .put("firstFrameMs", run.firstFrameMs)
            .put("seekToReadyMs", run.seekToReadyMs)
            .put("bitmapCues", run.bitmapCues)
            .put("textCues", run.textCues)
            .put("droppedFrames", run.droppedFrames)
            .put("error", run.error?.errorCodeName)
            .put("png", png.name.takeIf { png.isFile })
        Log.i(TAG, "RESULT $result")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("result", "$result") })

        assertNull(run.error)
        assertTrue("No first frame", run.firstFrameMs > 0)
        assertTrue("Seek never got ready", run.seekToReadyMs > 0)
        when (expect) {
            "libass" -> {
                assertTrue(run.libassActive)
                assertTrue("No libass bitmap cues", run.bitmapCues > 0)
                assertEquals(0, run.textCues)
            }
            "text" -> {
                assertTrue(!run.libassActive)
                assertTrue("No SSA text cues", run.textCues > 0)
                assertEquals(0, run.bitmapCues)
            }
            else -> error("Unknown expect $expect")
        }
    }

    private inner class PlayerRun(private val file: File, private val libass: LibassSubtitles?) {
        private val thread = HandlerThread("libass-proof").apply { start() }
        private val handler = Handler(thread.looper)
        private val firstFrame = CountDownLatch(1)

        @Volatile var libassActive = false
        @Volatile var firstFrameMs = -1L
        @Volatile var seekToReadyMs = -1L
        @Volatile var bitmapCues = 0
        @Volatile var textCues = 0
        @Volatile var droppedFrames = 0
        @Volatile var error: PlaybackException? = null
        @Volatile private var richestCues: List<Cue> = emptyList()
        @Volatile private var videoAspectRatio = DEFAULT_ASPECT_RATIO

        fun play() {
            val reader = ImageReader.newInstance(SURFACE_WIDTH, SURFACE_HEIGHT, ImageFormat.PRIVATE, SURFACE_IMAGES)
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
            val startedAt = SystemClock.elapsedRealtime()
            val player = onPlayer {
                playbackExoPlayer(context, libass) { setLooper(thread.looper) }.apply {
                    libassActive = (0 until rendererCount).any { getRenderer(it) is AssRenderer }
                    volume = 0f
                    setVideoSurface(reader.surface)
                    addAnalyticsListener(Listener(startedAt))
                    addListener(Cues())
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    prepare()
                    play()
                }
            }
            firstFrame.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            Thread.sleep(PLAY_BEFORE_SEEK_MILLIS)
            seekToReadyMs = seekAndAwaitReady(player)
            Thread.sleep(PLAY_MILLIS)
            onPlayer { player.release() }
            reader.close()
            thread.quitSafely()
        }

        fun drawCues(png: File) {
            val cues = richestCues.ifEmpty { return }
            val height = (CUE_WIDTH / videoAspectRatio).toInt()
            val bitmap = Bitmap.createBitmap(CUE_WIDTH, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(Color.DKGRAY) }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SubtitleCueRenderer(context).apply {
                    layOut(textScale = 1f, bottomPaddingFraction = 0.08f)
                    draw(cues, CUE_WIDTH, height, canvas)
                }
            }
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        }

        private fun seekAndAwaitReady(player: ExoPlayer): Long {
            val ready = CountDownLatch(1)
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }
            }
            val seekedAt = onPlayer {
                player.addListener(listener)
                player.seekTo(SEEK_MILLIS)
                SystemClock.elapsedRealtime()
            }
            val done = ready.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            val elapsed = SystemClock.elapsedRealtime() - seekedAt
            onPlayer { player.removeListener(listener) }
            return if (done) elapsed else -1L
        }

        private fun <T> onPlayer(block: () -> T): T {
            var value: Result<T>? = null
            val done = CountDownLatch(1)
            handler.post {
                value = runCatching(block)
                done.countDown()
            }
            check(done.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { "The player thread stalled" }
            return checkNotNull(value).getOrThrow()
        }

        private inner class Cues : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                if (cueGroup.cues.isEmpty()) return
                if (cueGroup.cues.size >= richestCues.size) richestCues = cueGroup.cues
                bitmapCues += cueGroup.cues.count { it.bitmap != null }
                textCues += cueGroup.cues.count { it.bitmap == null && it.text != null }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoSize.displayAspectRatioOrNull()?.let { videoAspectRatio = it }
            }
        }

        private inner class Listener(private val startedAt: Long) : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (firstFrameMs < 0) firstFrameMs = SystemClock.elapsedRealtime() - startedAt
                firstFrame.countDown()
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
                droppedFrames += count
            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                this@PlayerRun.error = error
                firstFrame.countDown()
            }
        }
    }

    private companion object {
        const val TAG = "LibassProof"
        const val PROOF_CACHE_MB = 32
        const val PLAY_BEFORE_SEEK_MILLIS = 30_000L
        const val PLAY_MILLIS = 6_000L
        const val SEEK_MILLIS = 45_000L
        const val TIMEOUT_MILLIS = 60_000L
        const val SURFACE_WIDTH = 3_840
        const val SURFACE_HEIGHT = 2_160
        const val SURFACE_IMAGES = 4
        const val CUE_WIDTH = 1_920
        const val DEFAULT_ASPECT_RATIO = 16f / 9f
        const val PNG_QUALITY = 100
    }
}
