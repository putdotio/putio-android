package io.putdotio.android.playback.spike

import android.content.Context
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

internal class VlcSpikeEngine(
    context: Context,
    container: FrameLayout,
    private val request: SpikeRequest,
) : SpikeEngine {
    private val libVlc = LibVLC(context, arrayListOf("-vv", "--network-caching=$NETWORK_CACHING_MILLIS"))
    private val player = MediaPlayer(libVlc)
    private val layout = VLCVideoLayout(context)
    private var startedAt = 0L
    private var firstVoutMs: Long? = null
    private var firstPlayingMs: Long? = null
    private var errors = 0
    private var buffering = 0
    private var audioSelected = false
    private var seekTarget = -1L
    private var reachedSeek: CompletableDeferred<Unit>? = null

    init {
        container.addView(layout)
        player.attachViews(layout, null, true, false)
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Vout ->
                    if (event.voutCount > 0 && firstVoutMs == null) firstVoutMs = elapsed()
                MediaPlayer.Event.Playing -> if (firstPlayingMs == null) firstPlayingMs = elapsed()
                MediaPlayer.Event.Buffering -> if (event.buffering < FULL_BUFFER) buffering++
                MediaPlayer.Event.EncounteredError -> errors++
                MediaPlayer.Event.ESAdded -> selectAudio()
                MediaPlayer.Event.TimeChanged ->
                    if (seekTarget >= 0 && event.timeChanged >= seekTarget) reachedSeek?.complete(Unit)
            }
        }
    }

    override fun start(url: String, startedAt: Long) {
        this.startedAt = startedAt
        val media = Media(libVlc, url.toUri())
        media.setHWDecoderEnabled(true, false)
        player.media = media
        media.release()
        player.play()
    }

    override suspend fun seekAndAwait(positionMillis: Long): Long {
        val reached = CompletableDeferred<Unit>()
        reachedSeek = reached
        seekTarget = positionMillis
        val seekedAt = SystemClock.elapsedRealtime()
        player.setTime(positionMillis)
        return withTimeoutOrNull(SEEK_TIMEOUT_MILLIS) {
            reached.await()
            SystemClock.elapsedRealtime() - seekedAt
        } ?: -1L
    }

    override fun report(into: JSONObject) {
        val media = player.media
        val stats = media?.stats
        val tracks = JSONArray()
        media?.let { (0 until it.trackCount).mapNotNull(it::getTrack) }.orEmpty().forEach { track ->
            tracks.put(
                JSONObject()
                    .put("type", track.type)
                    .put("codec", track.codec)
                    .put("profile", track.profile)
                    .put("size", (track as? IMedia.VideoTrack)?.let { "${it.width}x${it.height}" })
                    .put("channels", (track as? IMedia.AudioTrack)?.channels),
            )
        }
        media?.release()
        into.put("ttffMs", firstVoutMs)
            .put("firstPlayingMs", firstPlayingMs)
            .put("decodedVideo", stats?.decodedVideo)
            .put("displayedFrames", stats?.displayedPictures)
            .put("droppedFrames", stats?.lostPictures)
            .put("lostAudioBuffers", stats?.lostAbuffers)
            .put("demuxCorrupted", stats?.demuxCorrupted)
            .put("bufferingEvents", buffering)
            .put("errors", errors)
            .put("audioTrack", player.audioTrack)
            .put("spuTrack", player.spuTrack)
            .put("audioTracks", JSONArray(player.audioTracks.orEmpty().map { it.name }))
            .put("spuTracks", JSONArray(player.spuTracks.orEmpty().map { it.name }))
            .put("tracks", tracks)
    }

    override fun release() {
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
        libVlc.release()
    }

    private fun selectAudio() {
        val target = player.audioTracks
            ?.filter { it.id >= 0 }
            ?.getOrNull(request.audioTrack)
            ?.takeIf { !audioSelected && request.audioTrack >= 0 }
        if (target != null) audioSelected = player.setAudioTrack(target.id)
    }

    private fun elapsed(): Long = SystemClock.elapsedRealtime() - startedAt
}

private const val NETWORK_CACHING_MILLIS = 1_500
private const val FULL_BUFFER = 100f
private const val SEEK_TIMEOUT_MILLIS = 20_000L
