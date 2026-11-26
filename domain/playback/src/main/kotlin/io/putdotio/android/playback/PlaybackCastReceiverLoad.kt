package io.putdotio.android.playback

import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PutioMediaInfo
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

public data class PlaybackCastReceiverLoad(
    public val fileId: Long,
    public val token: String,
    public val maxSubtitleCount: Int? = null,
    public val subtitleLanguages: List<String>? = null,
    public val mediaInfo: PutioMediaInfo? = null,
) {
    public fun customData(positionMillis: Long, maxBytes: Int): String {
        val load = buildJsonObject {
            put("fileId", fileId)
            put("method", HLS_METHOD)
            put("position", positionMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND)
            put("token", token)
            maxSubtitleCount?.let { put("maxSubtitleCount", it) }
            subtitleLanguages?.let { languages -> put("subtitleLanguages", JsonArray(languages.map(::JsonPrimitive))) }
        }
        val info = mediaInfo?.trimmed() ?: return load.toString()
        val streams = info.streams.orEmpty()
        val withStreams = { count: Int ->
            JsonObject(load + ("media_info" to CAST_JSON.encodeToJsonElement(info.copy(streams = streams.take(count)))))
                .toString()
        }
        var low = 0
        var high = streams.size
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (withStreams(middle).utf8Size() <= maxBytes) low = middle else high = middle - 1
        }
        return if (low > 0) withStreams(low) else load.toString()
    }

    override fun toString(): String =
        "PlaybackCastReceiverLoad(fileId=$fileId, maxSubtitleCount=$maxSubtitleCount, " +
            "subtitleLanguages=$subtitleLanguages, <redacted token>)"
}

internal fun PlaybackSource.toReceiverLoad(target: PlaybackTarget, downloadToken: String): PlaybackCastReceiverLoad? {
    val query = url.value.queryValues()
        ?.takeIf { kind == PlaybackSourceKind.HLS && target.mediaType == PlaybackMediaType.VIDEO }
        ?.takeUnless { it.containsKey(ORIGINAL_QUERY) }
        ?: return null
    return PlaybackCastReceiverLoad(
        fileId = fileId,
        token = downloadToken,
        maxSubtitleCount = query[MAX_SUBTITLE_COUNT_QUERY]?.toIntOrNull()?.takeIf { it >= 0 },
        subtitleLanguages = query[SUBTITLE_LANGUAGES_QUERY]?.split(',')
            ?.takeIf { languages -> languages.all(LANGUAGE_CODE::matches) },
        mediaInfo = mediaInfo,
    )
}

private fun PutioMediaInfo.trimmed(): PutioMediaInfo? {
    val all = streams
    val known = format
    if (all == null || known == null) return null
    return PutioMediaInfo(
        mimeType = mimeType,
        format = known,
        streams = all.sortedBy { STREAM_PRIORITY[it.codecType] ?: STREAM_PRIORITY.size },
        playbackIssues = playbackIssues.orEmpty(),
    )
}

private fun String.queryValues(): Map<String, String>? =
    try {
        URI(this).rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { pair ->
            pair.substringBefore('=').decoded() to pair.substringAfter('=', missingDelimiterValue = "").decoded()
        }
    } catch (_: URISyntaxException) {
        null
    }

private fun String.decoded(): String =
    try {
        URLDecoder.decode(this, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        this
    }

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private val CAST_JSON = Json { explicitNulls = false }
private val LANGUAGE_CODE = Regex("^[a-z]{3}$")
private val STREAM_PRIORITY = mapOf("video" to 0, "audio" to 1)
private const val HLS_METHOD = "hls"
private const val MILLIS_PER_SECOND = 1_000.0
private const val ORIGINAL_QUERY = "original"
private const val MAX_SUBTITLE_COUNT_QUERY = "max_subtitle_count"
private const val SUBTITLE_LANGUAGES_QUERY = "subtitle_languages"
