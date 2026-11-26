package io.putdotio.android.playback

import android.os.SystemClock
import androidx.core.net.toUri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject

internal fun Float.castRate(): Double =
    toDouble().coerceIn(MediaLoadRequestData.PLAYBACK_RATE_MIN, MediaLoadRequestData.PLAYBACK_RATE_MAX)

internal fun PlaybackCastMedia.failedAt(startMillis: Long): CastRemoteStatus =
    CastRemoteStatus(
        fileId = fileId,
        state = CastRemoteState.Failed,
        positionMillis = startMillis,
        durationMillis = durationMillis,
        sampledAtMillis = SystemClock.elapsedRealtime(),
        title = title,
        audio = mediaType == PlaybackMediaType.AUDIO,
    )

internal fun PlaybackCastMedia.loadRequest(
    startMillis: Long,
    autoplay: Boolean,
    speed: Float,
    putioReceiver: Boolean,
): MediaLoadRequestData {
    val receiverLoad = receiverLoad?.takeIf { putioReceiver }
    val plain = loadBuilder(startMillis, autoplay, speed, sendsUrl = receiverLoad == null).build()
    receiverLoad ?: return plain
    val room = CAST_MESSAGE_MAX_BYTES - CAST_ENVELOPE_BYTES
    val withCustomData = { budget: Int ->
        loadBuilder(startMillis, autoplay, speed, sendsUrl = false)
            .setCustomData(JSONObject(receiverLoad.customData(startMillis, budget)))
            .build()
    }
    val budget = room - plain.jsonSize()
    val first = withCustomData(budget)
    val over = first.jsonSize() - room
    return when {
        over <= 0 -> first
        else -> withCustomData(budget - over).takeIf { it.jsonSize() <= room } ?: withCustomData(0)
    }
}

private fun MediaLoadRequestData.jsonSize(): Int = toJson().toString().toByteArray().size

private fun PlaybackCastMedia.loadBuilder(startMillis: Long, autoplay: Boolean, speed: Float, sendsUrl: Boolean) =
    MediaLoadRequestData.Builder()
        .setMediaInfo(toMediaInfo(sendsUrl))
        .setAutoplay(autoplay)
        .setCurrentTime(startMillis)
        .setPlaybackRate(speed.castRate())
        .setActiveTrackIds(activeSubtitleId?.let { longArrayOf(it) } ?: longArrayOf())

internal fun MediaInfo.putioFileId(): Long? = customData?.optLong(FILE_ID_KEY)?.takeIf { it > 0L }

internal fun PlaybackCastMedia.toMediaInfo(sendsUrl: Boolean = true): MediaInfo {
    val kind = if (mediaType == PlaybackMediaType.AUDIO) {
        MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
    } else {
        MediaMetadata.MEDIA_TYPE_MOVIE
    }
    val metadata = MediaMetadata(kind).apply {
        putString(MediaMetadata.KEY_TITLE, title)
        posterUrl?.let { addImage(WebImage(it.toUri())) }
    }
    return MediaInfo.Builder(fileId.toString())
        .apply { if (sendsUrl) setContentUrl(url) }
        .setContentType(contentType)
        .setStreamType(if (buffered) MediaInfo.STREAM_TYPE_BUFFERED else MediaInfo.STREAM_TYPE_NONE)
        .setMetadata(metadata)
        .setMediaTracks(
            subtitles.map { subtitle ->
                MediaTrack.Builder(subtitle.id, MediaTrack.TYPE_TEXT)
                    .setContentId(subtitle.url)
                    .setContentType(WEBVTT_CONTENT_TYPE)
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setName(subtitle.name)
                    .setLanguage(subtitle.languageCode)
                    .build()
            },
        )
        .setCustomData(JSONObject().put(FILE_ID_KEY, fileId))
        .apply { durationMillis?.let(::setStreamDuration) }
        .build()
}

internal const val FILE_ID_KEY = "putioFileId"
private const val WEBVTT_CONTENT_TYPE = "text/vtt"
private const val CAST_MESSAGE_MAX_BYTES = 64 * 1024
private const val CAST_ENVELOPE_BYTES = 1024

internal fun List<MediaTrack>.castTracks(type: Int): List<CastTrack> =
    filter { it.type == type }.map { CastTrack(it.id, it.name?.takeIf(String::isNotBlank), it.language) }
