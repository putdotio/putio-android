package io.putdotio.android.playback

import android.content.Context
import androidx.core.content.edit
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.putioMono
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.media3.ui.SubtitleView
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import java.util.Locale
import kotlin.math.abs

/** Where a subtitle track comes from, in the order the sheet lists them. */
internal enum class SubtitleOrigin { File, Folder, OpenSubtitles }

/**
 * A track put.io listed is found by its key, which Media3 keeps as the format id (prefixed with the
 * merged period's index). Tracks put.io did not list come with the stream: they are in the file.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun PlaybackSubtitleTrack.putioSubtitle(sidecar: List<PlaybackSubtitle>): PlaybackSubtitle? {
    val id = identity.id ?: return null
    return sidecar.firstOrNull { id == it.key || id.endsWith(":${it.key}") }
}

internal fun PlaybackSubtitle?.origin(): SubtitleOrigin =
    when (this?.source) {
        null, SOURCE_MKV -> SubtitleOrigin.File
        SOURCE_FOLDER -> SubtitleOrigin.Folder
        else -> SubtitleOrigin.OpenSubtitles
    }

private const val SOURCE_MKV = "mkv"
private const val SOURCE_FOLDER = "folder"

/** The text sizes the viewer picks from, relative to Android's caption size. */
internal enum class SubtitleTextSize(
    val scale: Float,
    val short: String,
    @StringRes val label: Int,
) {
    Small(SMALL_SCALE, "S", R.string.mobile_playback_subtitle_size_small),
    Medium(1f, "M", R.string.mobile_playback_subtitle_size_medium),
    Large(LARGE_SCALE, "L", R.string.mobile_playback_subtitle_size_large),
    ExtraLarge(EXTRA_LARGE_SCALE, "XL", R.string.mobile_playback_subtitle_size_extra_large),
}

private const val SMALL_SCALE = 0.75f
private const val LARGE_SCALE = 1.25f
private const val EXTRA_LARGE_SCALE = 1.5f

/** One timing step, and how far either way the timing goes. */
internal const val SUBTITLE_OFFSET_STEP_MILLIS = 100L
internal const val SUBTITLE_OFFSET_LIMIT_MILLIS = 10_000L

/** The subtitle text size this device keeps across videos. */
@Stable
internal class SubtitleAppearanceStore(private val context: Context?) {
    var textSize by mutableStateOf(read())
        private set

    fun update(size: SubtitleTextSize) {
        textSize = size
        context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)?.edit {
            putString(TEXT_SIZE_KEY, size.name)
        }
    }

    private fun read(): SubtitleTextSize {
        val stored = context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.getString(TEXT_SIZE_KEY, null)
        return SubtitleTextSize.entries.firstOrNull { it.name == stored } ?: SubtitleTextSize.Medium
    }

    private companion object {
        const val PREFERENCES_NAME = "io.put.player"
        const val TEXT_SIZE_KEY = "subtitle_text_size"
    }
}

@Composable
internal fun rememberSubtitleAppearanceStore(): SubtitleAppearanceStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { SubtitleAppearanceStore(context) }
}

/** "+0.5 s", "−0.3 s", "0.0 s": the offset in seconds with one decimal and a real minus sign. */
internal fun formatSubtitleOffset(offsetMillis: Long): String {
    val seconds = String.format(Locale.ROOT, "%.1f", abs(offsetMillis) / MILLIS_PER_SECOND)
    return when {
        offsetMillis > 0 -> "+$seconds s"
        offsetMillis < 0 -> "−$seconds s"
        else -> "$seconds s"
    }
}

private const val MILLIS_PER_SECOND = 1_000.0

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
            ?: subtitleFormatName(track.group.getFormat(track.trackIndex).let { it.codecs ?: it.sampleMimeType })
    }

internal fun subtitleFormatName(mimeType: String?): String? =
    when (mimeType) {
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_VTT -> "VTT"
        MimeTypes.TEXT_SSA -> "SSA"
        MimeTypes.APPLICATION_TTML -> "TTML"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        MimeTypes.APPLICATION_DVBSUBS -> "DVB"
        MimeTypes.APPLICATION_TX3G -> "TX3G"
        MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_MP4CEA608 -> "CEA-608"
        MimeTypes.APPLICATION_CEA708 -> "CEA-708"
        else -> null
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
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
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

internal fun PlaybackSubtitles?.sidecarTracks(): List<PlaybackSubtitle> =
    (this as? PlaybackSubtitles.Sidecar)?.tracks.orEmpty()

/**
 * How far to raise the subtitles so they clear the times, scrubber and chips: the controls' height
 * less what already lies below the cues, the letterbox and Android's caption margin.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun subtitleLift(frame: DpSize, aspectRatio: Float?): Dp {
    if (aspectRatio == null || !aspectRatio.isFinite() || aspectRatio <= 0f) return 0.dp
    val videoHeight = minOf(frame.height, frame.width / aspectRatio)
    val letterbox = (frame.height - videoHeight) / 2
    val captionMargin = videoHeight * SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
    return (VIDEO_CONTROLS_HEIGHT - letterbox - captionMargin).coerceAtLeast(0.dp)
}

// The footer's times, scrubber and chips, plus a little air above them.
private val VIDEO_CONTROLS_HEIGHT = 118.dp
