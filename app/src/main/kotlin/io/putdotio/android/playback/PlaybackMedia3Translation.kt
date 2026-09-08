package io.putdotio.android.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import kotlin.math.roundToLong

private const val MILLIS_PER_SECOND = 1_000.0
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

internal data class PreparedPlayback(
    val mediaItem: MediaItem,
    val startPositionMillis: Long,
)

internal fun PlaybackSource.preparePlayback(
    title: String,
    mediaType: PlaybackMediaType = PlaybackMediaType.VIDEO,
    resumePositionMillis: Long? = null,
): PreparedPlayback =
    PreparedPlayback(
        mediaItem = toMediaItem(title, mediaType),
        startPositionMillis = resumePositionMillis ?: startFromSeconds.toPlaybackMillis(),
    )

internal fun PlaybackSource.toMediaItem(
    title: String,
    mediaType: PlaybackMediaType = PlaybackMediaType.VIDEO,
): MediaItem {
    val subtitleConfigurations =
        (subtitles as? PlaybackSubtitles.Sidecar)
            ?.tracks
            .orEmpty()
            .mapNotNull { subtitle ->
                val mimeType = subtitle.toSubtitleMimeType() ?: return@mapNotNull null
                subtitle to mimeType
            }.map { (subtitle, mimeType) ->
                MediaItem.SubtitleConfiguration.Builder(subtitle.url.value.toUri())
                    .setId(subtitle.key)
                    .setLabel(subtitle.name)
                    .setLanguage(subtitle.languageCode)
                    .setMimeType(mimeType)
                    .setSelectionFlags(0)
                    .build()
            }

    return MediaItem.Builder()
        .setMediaId(fileId.toString())
        .setUri(url.value)
        .setMimeType(if (kind == PlaybackSourceKind.HLS) MimeTypes.APPLICATION_M3U8 else null)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setMediaType(
                    when (mediaType) {
                        PlaybackMediaType.VIDEO -> MediaMetadata.MEDIA_TYPE_VIDEO
                        PlaybackMediaType.AUDIO -> MediaMetadata.MEDIA_TYPE_MUSIC
                    },
                )
                .build(),
        )
        .setSubtitleConfigurations(subtitleConfigurations)
        .build()
}

internal fun PlaybackSource.hasSelectableSubtitles(): Boolean =
    subtitles is PlaybackSubtitles.Embedded ||
        (subtitles as? PlaybackSubtitles.Sidecar)
            ?.tracks
            ?.any { it.toSubtitleMimeType() != null } == true

internal fun Throwable.toMediaRequestFailureOrNull(): PlaybackFailure? {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    var networkFailure: HttpDataSource.HttpDataSourceException? = null
    while (current != null && visited.add(current)) {
        when (current) {
            is HttpDataSource.InvalidResponseCodeException ->
                if (current.responseCode == HTTP_UNAUTHORIZED || current.responseCode == HTTP_FORBIDDEN) {
                    return PlaybackFailure.MediaCredentialUnavailable(this)
                }

            is HttpDataSource.HttpDataSourceException ->
                if (current.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    current.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                ) {
                    networkFailure = current
                }
        }
        current = current.cause
    }
    return networkFailure?.let { PlaybackFailure.NetworkUnavailable(this) }
}

internal fun PlaybackException.toPlaybackFailure(): PlaybackFailure =
    toMediaRequestFailureOrNull() ?: errorCodeFailureOrNull() ?: PlaybackFailure.Unexpected(this)

// An exception relayed through a MediaController keeps only its error code: the cause is
// rebuilt from a class name and message, or replaced by a RemoteException, so the
// HttpDataSource chain above cannot classify it.
private fun PlaybackException.errorCodeFailureOrNull(): PlaybackFailure? =
    when (errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackFailure.MediaCredentialUnavailable(this)
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> PlaybackFailure.NetworkUnavailable(this)

        else -> null
    }

private fun Double.toPlaybackMillis(): Long =
    (this * MILLIS_PER_SECOND)
        .coerceIn(0.0, Long.MAX_VALUE.toDouble())
        .roundToLong()

private fun String?.toSubtitleMimeType(): String? =
    when (this?.lowercase()) {
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ssa", "ass" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

private fun PlaybackSubtitle.toSubtitleMimeType(): String? =
    if (format == null) {
        url.encodedPath.substringAfterLast('.', missingDelimiterValue = "").toSubtitleMimeType()
    } else {
        format.toSubtitleMimeType()
    }
