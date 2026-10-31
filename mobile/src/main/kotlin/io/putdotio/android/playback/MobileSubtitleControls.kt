package io.putdotio.android.playback

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import io.putdotio.android.R
import io.putdotio.android.neutralTextButtonColors
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.putioMono
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.media3.ui.SubtitleView
import io.putdotio.sdk.files.PlaybackSubtitle
import java.util.Locale

/** The track the viewer picked, else the one showing; null when subtitles are off. */
private fun PlayerOptionsSnapshot.currentSubtitle(): PlaybackSubtitleTrack? {
    if (C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes) return null
    return subtitleTracks.singleOrNull { track ->
        parameters.overrides[track.group]?.trackIndices?.contains(track.trackIndex) == true
    } ?: subtitleTracks.firstOrNull { it.selected }
}

@Composable
internal fun MobileSubtitlesChip(
    snapshot: PlayerOptionsSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = snapshot.currentSubtitle()
    val label = current?.let { subtitleTrackName(it, snapshot.subtitleTracks.indexOf(it)) }
        ?: stringResource(R.string.mobile_playback_subtitles_disabled)
    MobilePlayerChip(
        label = label,
        description = stringResource(R.string.mobile_playback_subtitles_chip, label),
        onClick = onClick,
        leadingIcon = R.drawable.ic_ph_subtitles,
        modifier = modifier,
    )
}

/**
 * Subtitles: Off, then the tracks in the file, then the ones put.io found, then the Size and timing
 * row. A choice is recorded before the player gets it: the new parameters can change the tracks,
 * whose listeners read it.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobileSubtitlesSheetContent(
    player: Media3Player,
    snapshot: PlayerOptionsSnapshot,
    defaultTrackSelection: TrackSelectionParameters,
    sidecar: List<PlaybackSubtitle>,
    textSize: SubtitleTextSize,
    offsetMillis: Long,
    onSubtitleSelectionChanged: (SubtitleSelection) -> Unit,
    onAppearance: () -> Unit,
    onDone: () -> Unit,
) {
    fun select(selection: SubtitleSelection) {
        onSubtitleSelectionChanged(selection)
        player.trackSelectionParameters = player.trackSelectionParameters.withSubtitleSelection(
            selection = selection,
            tracks = snapshot.subtitleTracks,
            textDefaults = defaultTrackSelection,
        )
        onDone()
    }
    val current = snapshot.currentSubtitle()
    val enabled = snapshot.canSelectTracks
    val tracks = snapshot.subtitleTracks.mapIndexed { index, track ->
        Triple(index, track, track.putioSubtitle(sidecar))
    }
    Column(Modifier.selectableGroup()) {
        MobileSideSheetOption(
            label = stringResource(R.string.mobile_playback_subtitles_disabled),
            selected = current == null,
            enabled = enabled,
            onClick = { select(SubtitleSelection.Off) },
        )
        if (tracks.isEmpty()) {
            Text(
                stringResource(R.string.mobile_playback_no_captions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        val names = subtitleRowNames(tracks.map { (_, track, subtitle) -> track to subtitle }, tracks.map { it.first })
    val inFile = tracks.filter { it.third.origin() == SubtitleOrigin.File }
        val found = tracks.filter { it.third.origin() != SubtitleOrigin.File }.sortedBy { it.third.origin() }
        listOf(R.string.mobile_playback_subtitles_in_file to inFile, R.string.mobile_playback_subtitles_found to found)
            .filter { it.second.isNotEmpty() }
            .forEach { (heading, group) ->
                MobileSideSheetLabel(stringResource(heading))
                group.forEach { (index, track, subtitle) ->
                    MobileSideSheetOption(
                        label = names[index],
                        selected = track == current,
                        enabled = enabled,
                        supporting = subtitleTrackDetails(track, subtitle)?.let { { Text(it) } },
                        onClick = { select(SubtitleSelection.Track(track.identity)) },
                    )
                }
            }
    }
    MobileSubtitleAppearanceRow(textSize, offsetMillis, onAppearance)
}

@Composable
internal fun MobileSubtitleTrackOption(
    track: PlaybackSubtitleTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    MobileSideSheetOption(
        label = subtitleTrackName(track, track.trackIndex),
        selected = track.selected,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    )
}

/** Row names, numbered only where two rows would otherwise read the same. */
@Composable
private fun subtitleRowNames(
    rows: List<Pair<PlaybackSubtitleTrack, PlaybackSubtitle?>>,
    indices: List<Int>,
): List<String> {
    val names = rows.mapIndexed { position, (track, _) -> subtitleTrackName(track, indices[position]) }
    val details = rows.map { (track, subtitle) -> subtitleTrackDetails(track, subtitle) }
    val keyed = names.zip(details)
    return names.mapIndexed { position, name ->
        if (keyed.count { it == keyed[position] } > 1) {
            stringResource(R.string.mobile_playback_audio_track_disambiguated, name, indices[position] + 1)
        } else {
            name
        }
    }
}

/** A track's language in its own words, marked SDH where the format says so. */
@Composable
private fun subtitleTrackName(track: PlaybackSubtitleTrack, index: Int): String {
    val name = languageName(track.identity.language) ?: track.label
        ?: stringResource(R.string.mobile_playback_subtitle_track, index + 1)
    return if (track.identity.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) {
        name + DETAIL_SEPARATOR + stringResource(R.string.mobile_playback_subtitle_sdh)
    } else {
        name
    }
}

/** A track in the file names its format; one put.io found names where it found it. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun subtitleTrackDetails(track: PlaybackSubtitleTrack, subtitle: PlaybackSubtitle?): String? =
    when (subtitle.origin()) {
        SubtitleOrigin.Folder -> stringResource(R.string.mobile_playback_subtitles_from_folder)
        SubtitleOrigin.OpenSubtitles -> stringResource(R.string.mobile_playback_subtitles_from_opensubtitles)
        SubtitleOrigin.File -> subtitle?.format?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
            ?: track.formatName()
    }

@Composable
private fun MobileSubtitleAppearanceRow(textSize: SubtitleTextSize, offsetMillis: Long, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 4.dp),
    ) {
        Icon(painterResource(R.drawable.ic_ph_text_aa), contentDescription = null, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.mobile_playback_subtitle_appearance), style = PutioTypeScale.bodyMd)
            val sizeName = stringResource(textSize.label)
            Text(
                buildAnnotatedString {
                    append(sizeName)
                    append(DETAIL_SEPARATOR)
                    withStyle(SpanStyle(fontFamily = PutioTypeScale.mono.fontFamily, fontFeatureSettings = "tnum")) {
                        append(formatSubtitleOffset(offsetMillis))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painterResource(R.drawable.ic_ph_caret_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Size and timing: text size S to XL over Android's caption size, and a timing offset in 0.1 s
 * steps. Font and colours stay with Android's caption settings.
 */
@Composable
internal fun MobileSubtitleAppearanceContent(
    textSize: SubtitleTextSize,
    offsetMillis: Long,
    onTextSize: (SubtitleTextSize) -> Unit,
    onOffset: (Long) -> Unit,
) {
    MobileSideSheetLabel(stringResource(R.string.mobile_playback_subtitle_text_size))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SubtitleTextSize.entries.forEach { size ->
            val selected = size == textSize
            val name = stringResource(size.label)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onTextSize(size) })
                    .semantics { contentDescription = name },
            ) {
                val colors = MaterialTheme.colorScheme
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) colors.onSurface else colors.surfaceContainerHigh,
                    contentColor = if (selected) colors.surface else colors.onSurface,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Text(size.short, style = PutioTypeScale.mono) }
                }
            }
        }
    }
    MobileSideSheetLabel(stringResource(R.string.mobile_playback_subtitle_timing), Modifier.padding(top = 12.dp))
    // The 48dp touch bands of the steps and Reset already hold the board's spacing.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OffsetStep(
            label = "−0.1",
            description = stringResource(R.string.mobile_playback_subtitle_earlier),
            enabled = offsetMillis > -SUBTITLE_OFFSET_LIMIT_MILLIS,
            onClick = { onOffset(offsetMillis - SUBTITLE_OFFSET_STEP_MILLIS) },
        )
        Text(
            formatSubtitleOffset(offsetMillis),
            style = PutioTypeScale.mono.copy(fontSize = OFFSET_VALUE_SIZE, lineHeight = OFFSET_VALUE_LINE),
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        OffsetStep(
            label = "+0.1",
            description = stringResource(R.string.mobile_playback_subtitle_later),
            enabled = offsetMillis < SUBTITLE_OFFSET_LIMIT_MILLIS,
            onClick = { onOffset(offsetMillis + SUBTITLE_OFFSET_STEP_MILLIS) },
        )
    }
    TextButton(
        onClick = { onOffset(0L) },
        enabled = offsetMillis != 0L,
        modifier = Modifier.padding(horizontal = 12.dp),
        colors = neutralTextButtonColors(),
    ) { Text(stringResource(R.string.mobile_playback_subtitle_reset)) }
    Text(
        stringResource(R.string.mobile_playback_subtitle_caption_settings),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = PutioTypeScale.labelSm.fontSize),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
    )
}

@Composable
private fun OffsetStep(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    // The 48dp band holds the 32dp button, so its touch target overlaps nothing below.
    Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
        OffsetStepButton(label, description, enabled, onClick)
    }
}

@Composable
private fun OffsetStepButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(32.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.putioMono())
    }
}

private val OFFSET_VALUE_SIZE = 20.sp
private val OFFSET_VALUE_LINE = 28.sp

/**
 * The bottom padding, as a fraction of the video's height, that keeps bottom-placed subtitles
 * above [clearance] of controls: Android's caption margin, or more where the controls reach into
 * the picture past the letterbox.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun subtitleBottomPadding(frame: DpSize, aspectRatio: Float?, clearance: Dp): Float {
    val default = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
    val videoHeight = aspectRatio?.takeIf { it.isFinite() && it > 0f }
        ?.let { minOf(frame.height, frame.width / it) }
        ?.takeIf { it > 0.dp }
        ?: return default
    val letterbox = (frame.height - videoHeight) / 2
    val needed = (clearance + SUBTITLE_AIR - letterbox) / videoHeight
    // In a short window the controls can cover most of the picture; the cues stop at its middle.
    return if (clearance > 0.dp) needed.coerceIn(default, MAX_SUBTITLE_BOTTOM_PADDING) else default
}

private const val MAX_SUBTITLE_BOTTOM_PADDING = 0.5f

/** The uncovered part of the video: from [left], [width] wide and the video's full height. */
internal data class SubtitleArea(val left: Dp, val width: Dp, val height: Dp)

/**
 * Where subtitles lay out beside a sheet covering [coveredWidth] at the end: the part of the
 * centred video the sheet leaves, at the video's full height. Null when nothing covers it or the
 * video has no shape yet.
 */
internal fun subtitleArea(frame: DpSize, aspectRatio: Float?, coveredWidth: Dp): SubtitleArea? {
    val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f && coveredWidth > 0.dp } ?: return null
    val videoHeight = minOf(frame.height, frame.width / ratio)
    val videoLeft = (frame.width - videoHeight * ratio) / 2
    val right = minOf(videoLeft + videoHeight * ratio, frame.width - coveredWidth)
    return SubtitleArea(videoLeft, right - videoLeft, videoHeight)
        .takeIf { it.width > 0.dp && it.height > 0.dp }
}

// Between the controls and the lowest subtitle line.
private val SUBTITLE_AIR = 8.dp

@get:StringRes
internal val SubtitleTextSize.label: Int
    get() = when (this) {
        SubtitleTextSize.Small -> R.string.mobile_playback_subtitle_size_small
        SubtitleTextSize.Medium -> R.string.mobile_playback_subtitle_size_medium
        SubtitleTextSize.Large -> R.string.mobile_playback_subtitle_size_large
        SubtitleTextSize.ExtraLarge -> R.string.mobile_playback_subtitle_size_extra_large
    }
