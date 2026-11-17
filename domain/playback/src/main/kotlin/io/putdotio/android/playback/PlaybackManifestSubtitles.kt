package io.putdotio.android.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.putdotio.android.PutioFailure
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import kotlinx.coroutines.delay

internal fun List<PlaybackSubtitle>.keyedToManifest(tracks: List<PlaybackSubtitleTrack>): List<PlaybackSubtitle> {
    val listed = filter { it.format?.lowercase() in MANIFEST_FORMATS }.groupBy { it.manifestLanguage() }
    val names = tracks.map { it.identity.label?.let(::manifestName) }
    val positions = names.filterNotNull().groupBy({ it.language }, { it.position })
    return tracks.zip(names).mapNotNull { (track, name) ->
        val key = track.identity.trackKey() ?: return@mapNotNull null
        val language = name?.language ?: return@mapNotNull null
        val entries = listed[language]?.takeIf { positions[language]?.sorted() == (1..it.size).toList() }
        entries?.getOrNull(name.position - 1)?.copy(key = key)
    }
}

public class SubtitleSources(public val sidecar: List<PlaybackSubtitle>, public val originsKnown: Boolean)

public fun subtitleSources(
    subtitles: PlaybackSubtitles,
    listed: List<PlaybackSubtitle>?,
    tracks: List<PlaybackSubtitleTrack>,
): SubtitleSources {
    if (subtitles !is PlaybackSubtitles.Embedded) {
        return SubtitleSources(subtitles.sidecarTracks(), originsKnown = true)
    }
    val keyed = listed.orEmpty().keyedToManifest(tracks)
    return SubtitleSources(keyed, originsKnown = tracks.isNotEmpty() && keyed.size == tracks.size)
}

internal fun PlaybackSubtitle.fileStem(): String = name.trim().replace(SUBTITLE_EXTENSION, "")

public typealias PlaybackFindSubtitles = suspend (PlaybackTarget) -> PlaybackRepositoryResult<List<PlaybackSubtitle>>

@Composable
public fun rememberManifestSubtitles(
    state: PlaybackState,
    findSubtitles: PlaybackFindSubtitles?,
    onAuthenticationRequired: suspend () -> Unit,
): List<PlaybackSubtitle>? {
    val content = state.content as? PlaybackContent.Ready
    val manifest = content != null &&
        content.source.kind == PlaybackSourceKind.HLS &&
        state.target.mediaType == PlaybackMediaType.VIDEO &&
        !content.subtitlesHidden
    val currentOnAuthenticationRequired by rememberUpdatedState(onAuthenticationRequired)
    var listed by remember(state.target.fileId) { mutableStateOf<List<PlaybackSubtitle>?>(null) }
    LaunchedEffect(state.target.fileId, manifest, findSubtitles) {
        val find = findSubtitles?.takeIf { manifest && listed == null } ?: return@LaunchedEffect
        listed = findManifestSubtitles(state.target, find) { currentOnAuthenticationRequired() }
    }
    return listed.takeIf { manifest }
}

internal suspend fun findManifestSubtitles(
    target: PlaybackTarget,
    find: PlaybackFindSubtitles,
    onAuthenticationRequired: suspend () -> Unit,
): List<PlaybackSubtitle>? {
    var found = find(target)
    val retryDelays = MANIFEST_SUBTITLE_RETRY_DELAYS_MILLIS.iterator()
    while (found is PlaybackRepositoryResult.Failure && !found.rejectsSession() && retryDelays.hasNext()) {
        delay(retryDelays.next())
        found = find(target)
    }
    if (found.rejectsSession()) onAuthenticationRequired()
    return (found as? PlaybackRepositoryResult.Success)?.value
}

private fun PlaybackRepositoryResult<*>.rejectsSession(): Boolean =
    this is PlaybackRepositoryResult.Failure && failure.putioFailure is PutioFailure.AuthenticationRequired

private class ManifestName(val language: String, val position: Int)

private fun manifestName(label: String): ManifestName? {
    val name = label.trim().takeIf { it.isNotEmpty() } ?: return null
    val suffix = name.substringAfterLast(' ', "")
    val position = suffix.takeIf { it.length in 1..MAX_POSITION_DIGITS && it.all(Char::isDigit) }?.toInt()
    return if (position != null && position > 0) {
        ManifestName(name.substringBeforeLast(' ').trimEnd(), position)
    } else {
        ManifestName(name, 1)
    }
}

private fun PlaybackSubtitle.manifestLanguage(): String =
    language.trim().takeUnless { it.isEmpty() || languageCode == UNDETERMINED } ?: UNKNOWN_LANGUAGE

internal fun SubtitleTrackIdentity.trackKey(): String? =
    id ?: label?.let { "manifest:${language.orEmpty()}:$it" }

private val MANIFEST_FORMATS = setOf("srt", "webvtt", "vtt")
private val SUBTITLE_EXTENSION =
    Regex("\\.(srt|vtt|webvtt|ass|ssa|sub|smi|sbv|ttml|dfxp|txt)$", RegexOption.IGNORE_CASE)
private const val FIRST_RETRY_DELAY_MILLIS = 2_000L
private const val SECOND_RETRY_DELAY_MILLIS = 5_000L
private val MANIFEST_SUBTITLE_RETRY_DELAYS_MILLIS = listOf(FIRST_RETRY_DELAY_MILLIS, SECOND_RETRY_DELAY_MILLIS)
private const val MAX_POSITION_DIGITS = 3
private const val UNDETERMINED = "und"
private const val UNKNOWN_LANGUAGE = "Unknown"
