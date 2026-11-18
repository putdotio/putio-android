package io.putdotio.android.playback

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import androidx.media3.common.Format
import io.putdotio.sdk.files.PlaybackSubtitle

public const val TRACK_DETAIL_SEPARATOR: String = " · "

@Composable
public fun audioTrackLabels(tracks: List<PlaybackAudioTrack>): List<String> {
    val commentary = stringResource(R.string.playback_audio_commentary)
    val names = tracks.mapIndexed { index, track ->
        commentary.takeIf { track.isCommentary() }
            ?: languageName(track.identity.language) ?: track.label?.takeIf(String::isNotBlank)
            ?: stringResource(R.string.playback_audio_track_number, index + 1)
    }
    val details = tracks.map { audioTrackDetails(it) }
    return LocalResources.current.numberRepeatedRows(names, details)
}

@Composable
public fun audioTrackDetails(track: PlaybackAudioTrack): String? {
    val identity = track.identity
    val parts = listOfNotNull(
        audioCodecName(identity.sampleMimeType),
        when (identity.channelCount) {
            Format.NO_VALUE, 0 -> null
            1 -> stringResource(R.string.playback_audio_mono)
            2 -> stringResource(R.string.playback_audio_stereo)
            SURROUND_5_1 -> "5.1"
            SURROUND_7_1 -> "7.1"
            else -> pluralStringResource(
                R.plurals.playback_audio_channels,
                identity.channelCount,
                identity.channelCount,
            )
        },
        languageName(identity.language).takeIf { track.isCommentary() },
    )
    return parts.takeIf { it.isNotEmpty() }
        ?.joinToString(TRACK_DETAIL_SEPARATOR)
        ?.replaceFirstChar(Char::titlecaseChar)
}

private fun PlaybackAudioTrack.isCommentary(): Boolean = identity.roleFlags and C.ROLE_FLAG_COMMENTARY != 0

@Composable
public fun subtitleTrackLabels(
    tracks: List<PlaybackSubtitleTrack>,
    sidecar: List<PlaybackSubtitle>,
    inFile: Boolean = true,
): List<String> = LocalResources.current.subtitleTrackLabels(tracks, sidecar, inFile)

internal fun Resources.subtitleTrackLabels(
    tracks: List<PlaybackSubtitleTrack>,
    sidecar: List<PlaybackSubtitle>,
    inFile: Boolean,
): List<String> {
    val subtitles = tracks.map { it.putioSubtitle(sidecar) }
    val putioNames = subtitles.map { it?.let { subtitle -> putioSubtitleName(subtitle) } }.numberedRepeats()
    return numberRepeatedRows(
        names = tracks.mapIndexed { index, track -> subtitleTrackName(track, putioNames[index], inFile, index) },
        details = subtitles.map(::subtitleOriginDetail),
    )
}

private fun Resources.subtitleTrackName(
    track: PlaybackSubtitleTrack,
    putioName: String?,
    inFile: Boolean,
    index: Int,
): String {
    val manifestName = track.identity.label?.trim()?.takeIf { !inFile && it.isNotEmpty() }
    return putioName ?: manifestName ?: track.knownName() ?: getString(R.string.playback_subtitle_number, index + 1)
}

private fun Resources.putioSubtitleName(subtitle: PlaybackSubtitle): String {
    val language = subtitle.language.takeUnless { it.isBlank() || subtitle.languageCode == C.LANGUAGE_UNDETERMINED }
        ?: getString(R.string.playback_subtitle_unknown)
    return if (subtitle.name.isEmpty()) language else "$language - ${subtitle.fileStem()}"
}

private fun List<String?>.numberedRepeats(): List<String?> {
    val seen = mutableMapOf<String, Int>()
    return map { name ->
        name?.let {
            val count = seen.getOrDefault(it, 0) + 1
            seen[it] = count
            if (count > 1) "$it ($count)" else it
        }
    }
}

@Composable
public fun subtitleOriginDetail(subtitle: PlaybackSubtitle?): String? =
    LocalResources.current.subtitleOriginDetail(subtitle)

internal fun Resources.subtitleOriginDetail(subtitle: PlaybackSubtitle?): String? =
    when (subtitle.origin()) {
        SubtitleOrigin.Folder -> getString(R.string.playback_subtitles_from_folder)
        SubtitleOrigin.OpenSubtitles -> getString(R.string.playback_subtitles_from_opensubtitles)
        SubtitleOrigin.File -> null
    }

private fun Resources.numberRepeatedRows(names: List<String>, details: List<String?>): List<String> {
    val rows = names.zip(details)
    val numbered = names.mapIndexed { index, name ->
        if (rows.count { it == rows[index] } > 1) numberedTrack(name, index) else name
    }
    val numberedRows = numbered.zip(details)
    if (numberedRows.distinct().size == numberedRows.size) return numbered
    return names.mapIndexed { index, name -> numberedTrack(name, index) }
}

private fun Resources.numberedTrack(name: String, index: Int): String =
    getString(R.string.playback_track_disambiguated, name, index + 1)

private const val SURROUND_5_1 = 6
private const val SURROUND_7_1 = 8
