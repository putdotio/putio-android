package io.putdotio.android.playback

import androidx.media3.common.C
import io.putdotio.sdk.files.PlaybackSubtitle
import java.util.Locale

/**
 * This list keyed by the HLS manifest [tracks] it backs, so [putioSubtitle] finds each track's
 * source. The manifest lists put.io's subtitles in this order, numbered within each language;
 * empty unless every track finds its own subtitle. A track the manifest gives no id is keyed
 * by its language and name.
 */
public fun List<PlaybackSubtitle>.keyedToManifest(tracks: List<PlaybackSubtitleTrack>): List<PlaybackSubtitle> {
    val listedByLanguage = groupBy { it.languageCode.iso3Language() }
    val seen = mutableMapOf<String?, Int>()
    val keyed = tracks.map { track ->
        val language = track.identity.language.iso3Language()
        val ordinal = seen.getOrDefault(language, 0)
        seen[language] = ordinal + 1
        track.identity.trackKey()?.let { key -> listedByLanguage[language]?.getOrNull(ordinal)?.copy(key = key) }
    }
    val complete = keyed.filterNotNull()
    val unique = complete.map(PlaybackSubtitle::key).toSet().size == complete.size
    return complete.takeIf { size == tracks.size && it.size == tracks.size && unique }.orEmpty()
}

private fun String?.iso3Language(): String? {
    val tag = this?.substringBefore('-')?.substringBefore('_')?.lowercase()
        ?.takeUnless { it.isBlank() || it == C.LANGUAGE_UNDETERMINED } ?: return null
    return runCatching { Locale.forLanguageTag(tag).isO3Language }.getOrNull()?.takeUnless { it.isBlank() } ?: tag
}


internal fun SubtitleTrackIdentity.trackKey(): String? =
    id ?: label?.let { "manifest:${language.orEmpty()}:$it" }
