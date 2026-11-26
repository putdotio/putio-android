package io.putdotio.android.playback

import io.putdotio.android.playback.R as PlaybackR
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import io.putdotio.android.R

internal fun castTrackLabels(tracks: List<CastTrack>, fallback: String): List<String> {
    val labels = tracks.map { track ->
        track.name ?: languageName(track.language) ?: track.language?.takeIf(String::isNotBlank) ?: fallback
    }
    val counts = labels.groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return labels.map { label ->
        if (counts.getValue(label) == 1) {
            label
        } else {
            "$label ${seen.merge(label, 1, Int::plus)}"
        }
    }
}

internal fun CastRemoteStatus?.adoptedBy(item: MediaItem?): CastRemoteStatus? =
    this?.takeIf { it.fileId != null && item?.mediaId == it.fileId.toString() }

internal fun CastRemoteStatus.withAudio(audioId: Long): Set<Long> =
    activeTrackIds - audioTracks.map(CastTrack::id).toSet() + audioId

internal fun CastRemoteStatus.withSubtitle(textId: Long?): Set<Long> =
    activeTrackIds - textTracks.map(CastTrack::id).toSet() + listOfNotNull(textId)

@Composable
internal fun MobileCastTrackChips(status: CastRemoteStatus?, onSheet: (CastVideoSheet) -> Unit) {
    val tracks = status ?: return
    if (tracks.audioTracks.size > 1) {
        val labels = castTrackLabels(tracks.audioTracks, stringResource(R.string.mobile_playback_audio_control))
        val current = tracks.audioTracks.indexOfFirst { it.id in tracks.activeTrackIds }.takeIf { it >= 0 }
            ?.let(labels::get) ?: stringResource(R.string.mobile_playback_audio_control)
        MobilePlayerChip(
            label = current,
            description = stringResource(PlaybackR.string.playback_audio_chip, current),
            onClick = { onSheet(CastVideoSheet.Audio) },
            leadingIcon = R.drawable.ic_ph_speaker_high,
        )
    }
    if (tracks.textTracks.isNotEmpty()) {
        val labels = castTrackLabels(tracks.textTracks, stringResource(R.string.mobile_playback_subtitles_title))
        val current = tracks.textTracks.indexOfFirst { it.id in tracks.activeTrackIds }.takeIf { it >= 0 }
            ?.let(labels::get) ?: stringResource(R.string.mobile_playback_subtitles_disabled)
        MobilePlayerChip(
            label = current,
            description = stringResource(PlaybackR.string.playback_subtitles_chip, current),
            onClick = { onSheet(CastVideoSheet.Subtitles) },
            leadingIcon = R.drawable.ic_ph_subtitles,
        )
    }
}

@Composable
internal fun MobileCastAudioSheetContent(status: CastRemoteStatus, onTracks: (Set<Long>) -> Unit) {
    val labels = castTrackLabels(status.audioTracks, stringResource(R.string.mobile_playback_audio_control))
    Column(Modifier.selectableGroup()) {
        status.audioTracks.forEachIndexed { index, track ->
            MobileSideSheetOption(
                label = labels[index],
                selected = track.id in status.activeTrackIds,
                onClick = { onTracks(status.withAudio(track.id)) },
            )
        }
    }
}

@Composable
internal fun MobileCastSubtitlesSheetContent(status: CastRemoteStatus, onTracks: (Set<Long>) -> Unit) {
    val labels = castTrackLabels(status.textTracks, stringResource(R.string.mobile_playback_subtitles_title))
    Column(Modifier.selectableGroup()) {
        MobileSideSheetOption(
            label = stringResource(R.string.mobile_playback_subtitles_disabled),
            selected = status.textTracks.none { it.id in status.activeTrackIds },
            onClick = { onTracks(status.withSubtitle(null)) },
        )
        status.textTracks.forEachIndexed { index, track ->
            MobileSideSheetOption(
                label = labels[index],
                selected = track.id in status.activeTrackIds,
                onClick = { onTracks(status.withSubtitle(track.id)) },
            )
        }
    }
}
