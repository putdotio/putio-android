package io.putdotio.android.playback

import androidx.media3.common.C
import io.putdotio.sdk.files.PlaybackSubtitle
import java.util.Locale

/**
 * This list keyed by the HLS manifest [tracks] it backs, so [putioSubtitle] finds each track's
 * source. The manifest lists put.io's subtitles in this order, numbered within each language;
 * empty unless every track finds its subtitle.
 */
public fun List<PlaybackSubtitle>.keyedToManifest(tracks: List<PlaybackSubtitleTrack>): List<PlaybackSubtitle> {
    if (size != tracks.size) return emptyList()
    val listedByLanguage = groupBy { it.languageCode.iso3Language() }
    val seen = mutableMapOf<String?, Int>()
    return tracks.map { track ->
        val id = track.identity.id ?: return emptyList()
        val language = track.identity.language.iso3Language()
        val ordinal = seen.getOrDefault(language, 0)
        seen[language] = ordinal + 1
        listedByLanguage[language]?.getOrNull(ordinal)?.copy(key = id) ?: return emptyList()
    }
}

private fun String?.iso3Language(): String? {
    val tag = this?.substringBefore('-')?.substringBefore('_')?.lowercase()
        ?.takeUnless { it.isBlank() || it == C.LANGUAGE_UNDETERMINED } ?: return null
    return runCatching { Locale.forLanguageTag(tag).isO3Language }.getOrNull()?.takeUnless { it.isBlank() } ?: tag
}
