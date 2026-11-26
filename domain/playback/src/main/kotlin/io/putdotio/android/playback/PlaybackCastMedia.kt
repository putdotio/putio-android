package io.putdotio.android.playback

import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

public data class PlaybackCastMedia(
    public val fileId: Long,
    public val title: String,
    public val mediaType: PlaybackMediaType,
    public val url: String,
    public val contentType: String,
    public val buffered: Boolean,
    public val durationMillis: Long?,
    public val posterUrl: String?,
    public val subtitles: List<PlaybackCastSubtitle>,
    public val activeSubtitleId: Long?,
    public val playlistSubtitle: PlaybackCastPlaylistSubtitle? = null,
    public val receiverLoad: PlaybackCastReceiverLoad? = null,
) {
    override fun toString(): String =
        "PlaybackCastMedia(fileId=$fileId, contentType=$contentType, subtitles=${subtitles.size}, <redacted urls>)"
}

public data class PlaybackCastPlaylistSubtitle(public val language: String?)

public data class PlaybackCastSubtitle(
    public val id: Long,
    public val url: String,
    public val name: String,
    public val languageCode: String,
) {
    override fun toString(): String = "PlaybackCastSubtitle(id=$id, languageCode=$languageCode, <redacted url>)"
}

public fun PlaybackSource.toCastMedia(
    target: PlaybackTarget,
    downloadToken: String,
    activeSubtitleKey: String? = null,
): PlaybackCastMedia? {
    require(downloadToken.isNotBlank()) { "Download token must not be blank" }
    val mediaUrl = url.value.withDownloadToken(downloadToken)
        ?.takeUnless { kind == PlaybackSourceKind.ORIGINAL && target.mediaType == PlaybackMediaType.VIDEO }
        ?: return null
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
        posterUrl = target.posterUrl?.takeIf { it.startsWith("https://") && OAUTH_TOKEN_QUERY !in it.queryNames() },
        subtitles = castSubtitles,
        activeSubtitleId = castSubtitles.getOrNull(activeIndex)?.id,
        receiverLoad = toReceiverLoad(target, downloadToken),
    )
}

private fun PlaybackSource.castContentType(target: PlaybackTarget): String =
    when (kind) {
        PlaybackSourceKind.HLS -> HLS_CONTENT_TYPE
        PlaybackSourceKind.MP4 -> MP4_CONTENT_TYPE
        PlaybackSourceKind.ORIGINAL -> AUDIO_CONTENT_TYPES[target.name.extension()] ?: DEFAULT_AUDIO_CONTENT_TYPE
    }

private fun String.extension(): String = substringAfterLast('.', missingDelimiterValue = "").lowercase()

private fun String.withDownloadToken(token: String, vararg extra: Pair<String, String>): String? {
    val uri = apiUri() ?: return null
    val replaced = setOf(OAUTH_TOKEN_QUERY) + extra.map { it.first }
    val kept = uri.rawQuery.orEmpty().split('&')
        .filter { it.isNotEmpty() && it.substringBefore('=').decoded() !in replaced }
    val added = (extra.toList() + (OAUTH_TOKEN_QUERY to token)).map { (name, value) -> "$name=${value.encoded()}" }
    return "https://$PUTIO_API_HOST${uri.rawPath}?" + (kept + added).joinToString("&")
}

private fun String.apiUri(): URI? =
    try {
        URI(this).takeIf { it.scheme == "https" && it.host == PUTIO_API_HOST && !it.rawPath.isNullOrEmpty() }
    } catch (_: URISyntaxException) {
        null
    }

private fun String.queryNames(): Set<String> =
    try {
        URI(this).rawQuery.orEmpty().split('&').map { it.substringBefore('=').decoded() }.toSet()
    } catch (_: URISyntaxException) {
        emptySet()
    }

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
