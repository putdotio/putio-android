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
    val names = tracks.mapIndexed { index, track ->
        languageName(track.identity.language) ?: track.label?.takeIf(String::isNotBlank)
            ?: stringResource(R.string.playback_audio_track_number, index + 1)
    }
    return numberRepeatedRows(names, tracks.map { audioTrackDetails(it) })
}

@Composable
public fun audioTrackDetails(track: PlaybackAudioTrack): String? {
    val identity = track.identity
    val parts = listOfNotNull(
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
        audioCodecName(identity.sampleMimeType),
        stringResource(R.string.playback_audio_commentary)
            .takeIf { identity.roleFlags and C.ROLE_FLAG_COMMENTARY != 0 },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(TRACK_DETAIL_SEPARATOR)
}

@Composable
public fun subtitleTrackLabels(tracks: List<PlaybackSubtitleTrack>, sidecar: List<PlaybackSubtitle>): List<String> =
    numberRepeatedRows(
        names = tracks.mapIndexed { index, track -> subtitleTrackName(track, index) },
        details = tracks.map { subtitleTrackDetails(it, it.putioSubtitle(sidecar)) },
    )

@Composable
public fun subtitleTrackName(track: PlaybackSubtitleTrack, index: Int): String {
    val name = languageName(track.identity.language) ?: track.label?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.playback_subtitle_track, index + 1)
    return if (track.identity.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) {
        name + TRACK_DETAIL_SEPARATOR + stringResource(R.string.playback_subtitle_sdh)
    } else {
        name
    }
}

@Composable
public fun subtitleTrackDetails(track: PlaybackSubtitleTrack, subtitle: PlaybackSubtitle?): String? =
    when (subtitle.origin()) {
        SubtitleOrigin.Folder -> stringResource(R.string.playback_subtitles_from_folder)
        SubtitleOrigin.OpenSubtitles -> stringResource(R.string.playback_subtitles_from_opensubtitles)
        SubtitleOrigin.File -> subtitle?.format?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
            ?: track.formatName()
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

private const val SURROUND_5_1 = 6
private const val SURROUND_7_1 = 8
