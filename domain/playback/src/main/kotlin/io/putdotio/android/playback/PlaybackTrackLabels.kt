package io.putdotio.android.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import androidx.media3.common.Format
import io.putdotio.sdk.files.PlaybackSubtitle
import java.util.Locale

public const val TRACK_DETAIL_SEPARATOR: String = " · "

@Composable
public fun audioTrackLabels(tracks: List<PlaybackAudioTrack>): List<String> {
    val commentary = stringResource(R.string.playback_audio_commentary)
    val names = tracks.mapIndexed { index, track ->
        commentary.takeIf { track.isCommentary() }
            ?: languageName(track.identity.language) ?: track.label?.takeIf(String::isNotBlank)
            ?: stringResource(R.string.playback_audio_track_number, index + 1)
    }
    return numberRepeatedRows(names, tracks.map { audioTrackDetails(it) })
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
): List<String> =
    numberRepeatedRows(
        names = tracks.map { subtitleTrackName(it) },
        details = tracks.map { subtitleTrackDetails(it, it.putioSubtitle(sidecar), inFile) },
    )

@Composable
public fun subtitleTrackName(track: PlaybackSubtitleTrack): String {
    val name = languageName(track.identity.language)
        ?: track.label?.trim()?.takeUnless { it.isEmpty() || it.lowercase(Locale.ROOT) in UNKNOWN_LANGUAGE_NAMES }
        ?: stringResource(R.string.playback_subtitle_unknown)
    return if (track.identity.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) {
        name + TRACK_DETAIL_SEPARATOR + stringResource(R.string.playback_subtitle_sdh)
    } else {
        name
    }
}

/**
 * Where a subtitle comes from, else its format. [inFile] is false when the source cannot tell a
 * track embedded in the file from one put.io added, as in an HLS manifest.
 */
@Composable
public fun subtitleTrackDetails(track: PlaybackSubtitleTrack, subtitle: PlaybackSubtitle?, inFile: Boolean): String? =
    when (subtitle.origin()) {
        SubtitleOrigin.Folder -> stringResource(R.string.playback_subtitles_from_folder)
        SubtitleOrigin.OpenSubtitles -> stringResource(R.string.playback_subtitles_from_opensubtitles)
        SubtitleOrigin.File ->
            if (inFile && subtitle == null && languageName(track.identity.language) == null) {
                stringResource(R.string.playback_subtitles_embedded)
            } else {
                subtitle?.format?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: track.formatName()
            }
    }

@Composable
private fun numberRepeatedRows(names: List<String>, details: List<String?>): List<String> {
    val rows = names.zip(details)
    val numbered = names.mapIndexed { index, name ->
        if (rows.count { it == rows[index] } > 1) numberedTrack(name, index) else name
    }
    val numberedRows = numbered.zip(details)
    if (numberedRows.distinct().size == numberedRows.size) return numbered
    return names.mapIndexed { index, name -> numberedTrack(name, index) }
}

@Composable
private fun numberedTrack(name: String, index: Int): String =
    stringResource(R.string.playback_track_disambiguated, name, index + 1)

private val UNKNOWN_LANGUAGE_NAMES = setOf("unknown", "undetermined", "und")
private const val SURROUND_5_1 = 6
private const val SURROUND_7_1 = 8
