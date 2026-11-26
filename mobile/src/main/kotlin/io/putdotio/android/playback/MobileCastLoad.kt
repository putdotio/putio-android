package io.putdotio.android.playback

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject

/** Cast takes 0.5x to 2x; a local speed outside that hands over at the nearest end. */
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

/**
 * The LOAD for this media. The put.io receiver gets [PlaybackCastMedia.receiverLoad] and picks the
 * stream itself, so the token-bearing URL stays here; any other receiver plays [PlaybackCastMedia.url].
 */
@VisibleForTesting
internal fun PlaybackCastMedia.loadRequest(
    startMillis: Long,
    autoplay: Boolean,
    speed: Float,
    putioReceiver: Boolean,
): MediaLoadRequestData {
    val receiverLoad = receiverLoad?.takeIf { putioReceiver }
    return MediaLoadRequestData.Builder()
        .setMediaInfo(toMediaInfo(sendsUrl = receiverLoad == null))
        .setAutoplay(autoplay)
        .setCurrentTime(startMillis)
        .setPlaybackRate(speed.castRate())
        .setActiveTrackIds(activeSubtitleId?.let { longArrayOf(it) } ?: longArrayOf())
        .apply { receiverLoad?.let { setCustomData(JSONObject(it.customData(startMillis))) } }
        .build()
}

/**
 * The put.io file this media belongs to; null for media another sender sent. The put.io receiver
 * names it in the content id as well.
 */
internal fun MediaInfo.putioFileId(putioReceiver: Boolean): Long? =
    customData?.optLong(FILE_ID_KEY)?.takeIf { it > 0L }
        ?: contentId?.toLongOrNull()?.takeIf { putioReceiver && it > 0L }

/** The Cast form of [PlaybackCastMedia], tagged with its file id. */
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

/** Ties the receiver's media to the put.io file; another sender's media carries none. */
internal const val FILE_ID_KEY = "putioFileId"
private const val WEBVTT_CONTENT_TYPE = "text/vtt"
