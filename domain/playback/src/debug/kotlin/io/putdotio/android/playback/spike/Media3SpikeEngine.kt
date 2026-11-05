package io.putdotio.android.playback.spike

import android.content.Context
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import io.github.peerless2012.ass.media.type.AssRenderType
import io.putdotio.android.playback.playbackRenderersFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

@UnstableApi
internal class Media3SpikeEngine(
    context: Context,
    container: FrameLayout,
    private val request: SpikeRequest,
    libass: Boolean,
) : SpikeEngine {
    private val view = PlayerView(context).apply { useController = false }
    private val player: ExoPlayer
    private var startedAt = 0L
    private var firstFrameMs: Long? = null
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var error: String? = null
    private var rebuffers = 0
    private var wasReady = false
    private var audioOverridden = false
    private var readyAfterSeek: CompletableDeferred<Unit>? = null

    init {
        val renderers = playbackRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val builder = ExoPlayer.Builder(context)
        if (request.tvBuffer) {
            builder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(TV_MIN_BUFFER, TV_MAX_BUFFER, TV_START_BUFFER, TV_REBUFFER)
                    .build(),
            )
        }
        player = if (libass) {
            builder.buildWithAssSupport(context, AssRenderType.CUES, renderersFactory = renderers)
        } else {
            builder.setRenderersFactory(renderers).build()
        }
        player.addAnalyticsListener(Analytics())
        player.addListener(Events())
        view.player = player
        container.addView(view)
    }

    override fun start(url: String, startedAt: Long) {
        this.startedAt = startedAt
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    override suspend fun seekAndAwait(positionMillis: Long): Long {
        val ready = CompletableDeferred<Unit>()
        readyAfterSeek = ready
        val seekedAt = SystemClock.elapsedRealtime()
        player.seekTo(positionMillis)
        return withTimeoutOrNull(SEEK_TIMEOUT_MILLIS) {
            ready.await()
            SystemClock.elapsedRealtime() - seekedAt
        } ?: -1L
    }

    override fun report(into: JSONObject) {
        val video = player.videoDecoderCounters?.apply { ensureUpdated() }
        into.put("ttffMs", firstFrameMs)
            .put("videoDecoder", videoDecoder)
            .put("audioDecoder", audioDecoder)
            .put("videoFormat", player.videoFormat?.describe())
            .put("audioFormat", player.audioFormat?.describe())
            .put("renderedFrames", video?.renderedOutputBufferCount)
            .put("droppedFrames", video?.droppedBufferCount)
            .put("skippedFrames", video?.skippedOutputBufferCount)
            .put("maxConsecutiveDropped", video?.maxConsecutiveDroppedBufferCount)
            .put("rebuffers", rebuffers)
            .put("error", error)
            .put("verdict", directPlayVerdict(player.currentTracks))
            .put("tracks", player.currentTracks.describe())
            .put("cuesNow", player.currentCues.cues.size)
    }

    override fun release() {
        view.player = null
        player.release()
    }

    private inner class Events : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                wasReady = true
                readyAfterSeek?.complete(Unit)
            } else if (playbackState == Player.STATE_BUFFERING && wasReady && readyAfterSeek == null) {
                rebuffers++
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (audioOverridden || request.audioTrack < 0) return
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(request.audioTrack) ?: return
            audioOverridden = true
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(audio.mediaTrackGroup, 0))
                .build()
        }

        override fun onPlayerError(error: PlaybackException) {
            this@Media3SpikeEngine.error = "${error.errorCodeName}: ${error.cause?.javaClass?.simpleName}"
        }
    }

    private inner class Analytics : AnalyticsListener {
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            if (firstFrameMs == null) firstFrameMs = SystemClock.elapsedRealtime() - startedAt
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            videoDecoder = decoderName
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            audioDecoder = decoderName
        }
    }
}

@UnstableApi
internal fun directPlayVerdict(tracks: Tracks): String {
    val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    return when {
        video.isEmpty() -> "unknown:no-video-track"
        video.none { it.isSupported(false) } -> "fallback:video-unsupported"
        audio.isNotEmpty() && audio.none { it.isSupported(false) } -> "fallback:audio-unsupported"
        else -> "direct"
    }
}

@UnstableApi
private fun Tracks.describe(): JSONArray =
    JSONArray(
        groups.flatMap { group ->
            (0 until group.length).map { index ->
                JSONObject()
                    .put("type", group.type)
                    .put("format", group.getTrackFormat(index).describe())
                    .put("support", Util.getFormatSupportString(group.getTrackSupport(index)))
                    .put("selected", group.isTrackSelected(index))
            }
        },
    )

@UnstableApi
private fun Format.describe(): String =
    listOfNotNull(
        sampleMimeType,
        codecs,
        width.takeIf { it > 0 }?.let { "${it}x$height" },
        colorInfo?.toLogString(),
        channelCount.takeIf { it > 0 }?.let { "${it}ch" },
        label,
    ).joinToString(" ")

private const val SEEK_TIMEOUT_MILLIS = 20_000L
private const val TV_MIN_BUFFER = 8_000
private const val TV_MAX_BUFFER = 30_000
private const val TV_START_BUFFER = 1_500
private const val TV_REBUFFER = 3_000
