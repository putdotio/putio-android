package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.DirectPlayDownload
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.files.FileSubtitle
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PutioCredentialUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

internal data class DownloadSubtitle(
    val key: String,
    val url: String,
    val format: String?,
    val language: String,
    val languageCode: String,
    val name: String,
    val source: String,
)

internal class DownloadSources(
    val setting: () -> StreamingMethod = { StreamingMethod.Hls },
    val directPlay: suspend (FilesItemId, includeSubtitles: Boolean, chosen: Boolean) -> DirectPlayDownload? =
        { _, _, _ -> null },
)

internal fun FileSubtitle.toDownloadSubtitle(): DownloadSubtitle? {
    val parsed = url.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == API_HOST } ?: return null
    val tokenFree = parsed.newBuilder().removeAllQueryParameters(OAUTH_TOKEN_QUERY).build()
    return DownloadSubtitle(key, tokenFree.toString(), format, language, languageCode, name, source)
}

internal fun DownloadSubtitle.toPlaybackSubtitle(credentialUrl: (String) -> PutioCredentialUrl): PlaybackSubtitle =
    PlaybackSubtitle(format, key, language, languageCode, name, source, credentialUrl(url))

internal fun List<DownloadSubtitle>.toJson(): JSONArray =
    JSONArray().also { array ->
        for (subtitle in this) {
            array.put(
                JSONObject()
                    .put("key", subtitle.key)
                    .put("url", subtitle.url)
                    .put("language", subtitle.language)
                    .put("languageCode", subtitle.languageCode)
                    .put("name", subtitle.name)
                    .put("source", subtitle.source)
                    .also { json -> subtitle.format?.let { json.put("format", it) } },
            )
        }
    }

internal fun JSONArray?.toDownloadSubtitles(): List<DownloadSubtitle> =
    if (this == null) {
        emptyList()
    } else {
        (0 until length()).mapNotNull { index -> optJSONObject(index)?.toDownloadSubtitleOrNull() }
    }

private fun JSONObject.toDownloadSubtitleOrNull(): DownloadSubtitle? {
    val key = optString("key")
    val url = optString("url")
    return if (key.isBlank() || url.isBlank()) {
        null
    } else {
        DownloadSubtitle(
            key = key,
            url = url,
            format = optString("format").takeIf { has("format") && it.isNotBlank() },
            language = optString("language"),
            languageCode = optString("languageCode"),
            name = optString("name"),
            source = optString("source"),
        )
    }
}

internal fun downloadRequestData(name: String, subtitles: List<DownloadSubtitle>): ByteArray =
    if (subtitles.isEmpty()) {
        name.encodeToByteArray()
    } else {
        JSONObject().put("name", name).put("subtitles", subtitles.toJson()).toString().encodeToByteArray()
    }

internal class DownloadRequestData(val name: String, val subtitles: List<DownloadSubtitle>)

internal fun ByteArray.toDownloadRequestData(): DownloadRequestData {
    val text = decodeToString()
    val json = text.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.takeIf { it.opt("name") is String }
    return if (json == null) {
        DownloadRequestData(text, emptyList())
    } else {
        DownloadRequestData(json.optString("name"), json.optJSONArray("subtitles").toDownloadSubtitles())
    }
}

private const val API_HOST = "api.put.io"
private const val OAUTH_TOKEN_QUERY = "oauth_token"
