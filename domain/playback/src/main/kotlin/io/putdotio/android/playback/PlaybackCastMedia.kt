package io.putdotio.android.playback

import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a Cast receiver plays: the source the app resolved, with every URL carrying the account's
 * download token, never the session's access token. The receiver fetches each URL itself, without
 * the app's headers, so the token rides in the query; only put.io's API host receives it.
 */
public data class PlaybackCastMedia(
    public val fileId: Long,
    public val title: String,
    public val mediaType: PlaybackMediaType,
    public val url: String,
    public val contentType: String,
    /**
     * Whether the receiver may treat the stream as a file of known length. HLS is left to the
     * receiver: put.io's playlists carry no stable duration, so it reads seekability from the
     * stream, as iOS sends it.
     */
    public val buffered: Boolean,
    public val durationMillis: Long?,
    public val posterUrl: String?,
    public val subtitles: List<PlaybackCastSubtitle>,
    /** The subtitle track to show from the start; null for none. */
    public val activeSubtitleId: Long?,
) {
    override fun toString(): String =
        "PlaybackCastMedia(fileId=$fileId, contentType=$contentType, subtitles=${subtitles.size}, <redacted urls>)"
}

/** A subtitle as a WebVTT text track; [id] is the receiver's track id. */
public data class PlaybackCastSubtitle(
    public val id: Long,
    public val url: String,
    public val name: String,
    public val languageCode: String,
) {
    override fun toString(): String = "PlaybackCastSubtitle(id=$id, languageCode=$languageCode, <redacted url>)"
}

/**
 * The Cast form of this source, or null when it cannot go to a receiver: a URL that is not put.io's
 * HTTPS API, such as one only this device's cache can answer. Video goes as the source the account's
 * playback setting chose, HLS or MP4; audio as the original. Sidecar subtitles, which MP4 and
 * original sources list, go as WebVTT tracks; HLS carries its own in the playlist.
 */
public fun PlaybackSource.toCastMedia(
    target: PlaybackTarget,
    downloadToken: String,
    activeSubtitleKey: String? = null,
): PlaybackCastMedia? {
    require(downloadToken.isNotBlank()) { "Download token must not be blank" }
    val mediaUrl = url.value.withDownloadToken(downloadToken) ?: return null
    val sidecar = (subtitles as? PlaybackSubtitles.Sidecar)?.tracks.orEmpty()
    val tracks = sidecar.mapNotNull { subtitle ->
        subtitle.url.value.withDownloadToken(downloadToken, SUBTITLE_FORMAT_QUERY to WEBVTT)?.let { subtitle to it }
    }
    val castSubtitles = tracks.mapIndexed { index, (subtitle, trackUrl) ->
        PlaybackCastSubtitle(
            id = index + 1L,
            url = trackUrl,
            name = subtitle.language.ifBlank { subtitle.name },
            languageCode = subtitle.languageCode,
        )
    }
    val activeIndex = tracks.indexOfFirst { (subtitle, _) -> subtitle.key == activeSubtitleKey }
    return PlaybackCastMedia(
        fileId = fileId,
        title = target.name,
        mediaType = target.mediaType,
        url = mediaUrl,
        contentType = castContentType(target),
        buffered = kind != PlaybackSourceKind.HLS,
        durationMillis = target.durationSeconds?.toPlaybackMillis()?.takeIf { it > 0L },
        // A poster is a token-free still; one that carries a credential stays here.
        posterUrl = target.posterUrl?.takeIf { it.startsWith("https://") && OAUTH_TOKEN_QUERY !in it.queryNames() },
        subtitles = castSubtitles,
        activeSubtitleId = castSubtitles.getOrNull(activeIndex)?.id,
    )
}

private fun PlaybackSource.castContentType(target: PlaybackTarget): String =
    when (kind) {
        PlaybackSourceKind.HLS -> HLS_CONTENT_TYPE
        PlaybackSourceKind.MP4 -> MP4_CONTENT_TYPE
        PlaybackSourceKind.ORIGINAL -> when (target.mediaType) {
            PlaybackMediaType.AUDIO -> AUDIO_CONTENT_TYPES[target.name.extension()] ?: DEFAULT_AUDIO_CONTENT_TYPE
            PlaybackMediaType.VIDEO -> VIDEO_CONTENT_TYPES[target.name.extension()] ?: MP4_CONTENT_TYPE
        }
    }

private fun String.extension(): String = substringAfterLast('.', missingDelimiterValue = "").lowercase()

/**
 * This API URL with the download token as its only credential, plus [extra] query values; null for
 * any other host or scheme. Whatever credential the URL held, the session's access token included,
 * is dropped.
 */
private fun String.withDownloadToken(token: String, vararg extra: Pair<String, String>): String? {
    val uri = apiUri() ?: return null
    val replaced = setOf(OAUTH_TOKEN_QUERY) + extra.map { it.first }
    val kept = uri.rawQuery.orEmpty().split('&')
        // Names compare decoded, so an escaped `oauth%5Ftoken` cannot carry the session's token through.
        .filter { it.isNotEmpty() && it.substringBefore('=').decoded() !in replaced }
    val added = (extra.toList() + (OAUTH_TOKEN_QUERY to token)).map { (name, value) -> "$name=${value.encoded()}" }
    return "https://$PUTIO_API_HOST${uri.rawPath}?" + (kept + added).joinToString("&")
}

/** This URL when it is put.io's HTTPS API; null for any other host, scheme or a malformed URL. */
@Suppress("SwallowedException")
private fun String.apiUri(): URI? =
    try {
        URI(this).takeIf { it.scheme == "https" && it.host == PUTIO_API_HOST && !it.rawPath.isNullOrEmpty() }
    } catch (_: URISyntaxException) {
        null
    }

/** This URL's query names, decoded; empty for a malformed URL. */
@Suppress("SwallowedException")
private fun String.queryNames(): Set<String> =
    try {
        URI(this).rawQuery.orEmpty().split('&').map { it.substringBefore('=').decoded() }.toSet()
    } catch (_: URISyntaxException) {
        emptySet()
    }

@Suppress("SwallowedException")
private fun String.decoded(): String =
    try {
        URLDecoder.decode(this, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        this
    }

private fun String.encoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

private const val PUTIO_API_HOST = "api.put.io"
private const val OAUTH_TOKEN_QUERY = "oauth_token"
private const val SUBTITLE_FORMAT_QUERY = "format"
private const val WEBVTT = "webvtt"
private const val HLS_CONTENT_TYPE = "application/x-mpegURL"
private const val MP4_CONTENT_TYPE = "video/mp4"
private const val DEFAULT_AUDIO_CONTENT_TYPE = "audio/mpeg"
private val AUDIO_CONTENT_TYPES = mapOf(
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "m4b" to "audio/mp4",
    "aac" to "audio/aac",
    "flac" to "audio/flac",
    "ogg" to "audio/ogg",
    "oga" to "audio/ogg",
    "opus" to "audio/ogg",
    "wav" to "audio/wav",
)
private val VIDEO_CONTENT_TYPES = mapOf(
    "mp4" to MP4_CONTENT_TYPE,
    "m4v" to MP4_CONTENT_TYPE,
    "webm" to "video/webm",
)
