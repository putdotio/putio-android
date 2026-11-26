package io.putdotio.android.playback

import android.view.KeyEvent
import io.putdotio.android.playback.R as PlaybackR
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import io.putdotio.android.MobileKeyCommand
import io.putdotio.android.MobileShortcutScope
import io.putdotio.android.R
import io.putdotio.android.mobileKeyCommand
import io.putdotio.android.MobileIconButton
import io.putdotio.sdk.files.PlaybackSubtitles

internal val MobileVideoSheet.isSubtitles: Boolean
    get() = this == MobileVideoSheet.Subtitles || this == MobileVideoSheet.SubtitleAppearance

@Composable
internal fun MobileVideoChips(
    options: PlayerOptionsSnapshot,
    playerSubtitles: PlaybackSubtitles,
    subtitleStartupPolicy: SubtitleStartupPolicy?,
    stream: PlaybackStream?,
    onSheet: (MobileVideoSheet) -> Unit,
    modifier: Modifier,
) {
    MobileAudioChip(options, { onSheet(MobileVideoSheet.Audio) }, modifier)
    if (subtitleStartupPolicy?.showSubtitles != false) {
        MobileSubtitlesChip(options, playerSubtitles, { onSheet(MobileVideoSheet.Subtitles) }, modifier)
    }
    MobileSpeedChip(options, { onSheet(MobileVideoSheet.Speed) }, modifier)
    if (stream != null) {
        val label = stringResource(stream.label)
        MobilePlayerChip(
            label = label,
            description = stringResource(PlaybackR.string.playback_stream_chip, label),
            onClick = { onSheet(MobileVideoSheet.Stream) },
            leadingIcon = R.drawable.ic_ph_film_strip,
            modifier = modifier,
        )
    }
}

@Composable
internal fun MobileNextEpisodeChip(nextEpisode: PlaybackTarget?, onNextEpisode: () -> Unit, modifier: Modifier) {
    if (nextEpisode == null) return
    MobilePlayerChip(
        label = stringResource(PlaybackR.string.playback_next_episode),
        description = null,
        onClick = onNextEpisode,
        trailingIcon = R.drawable.ic_ph_skip_forward,
        modifier = modifier,
    )
}

@Composable
internal fun MobilePictureInPictureButton(enter: (() -> Unit)?, modifier: Modifier) {
    if (enter == null) return
    MobileIconButton(
        stringResource(R.string.mobile_playback_picture_in_picture),
        R.drawable.ic_ph_picture_in_picture,
        enter,
        modifier = modifier,
    )
}

internal fun videoKeyCommand(
    event: KeyEvent,
    sheet: MobileVideoSheet?,
    onCloseSheet: () -> Unit,
    run: (MobileKeyCommand) -> Unit,
): Boolean =
    when {
        sheet == null -> playerKeyCommand(event, run)
        mobileKeyCommand(MobileShortcutScope.Player, event.keyCode, event.metaState) == MobileKeyCommand.Back ->
            playerKeyCommand(event) { onCloseSheet() }
        else -> false
    }

internal fun MobileVideoSheet?.parentSheet(): MobileVideoSheet? =
    if (this == MobileVideoSheet.SubtitleAppearance) MobileVideoSheet.Subtitles else null

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobileVideoSheets(
    sheet: MobileVideoSheet?,
    onSheet: (MobileVideoSheet?) -> Unit,
    player: Media3Player,
    options: PlayerOptionsSnapshot,
    defaultTrackSelection: TrackSelectionParameters,
    playerSubtitles: PlaybackSubtitles,
    appearance: SubtitleAppearanceStore,
    preferences: RetainedPlayerPreferences,
    onSubtitleSelectionChanged: (SubtitleSelection) -> Unit,
    onAudioSelectionChanged: (AudioSelection) -> Unit,
    stream: PlaybackStreamSwitch?,
    modifier: Modifier,
    panelModifier: Modifier,
) {
    var shown by remember { mutableStateOf(sheet ?: MobileVideoSheet.Audio) }
    if (sheet != null) shown = sheet
    val close = { onSheet(null) }
    val context = LocalContext.current
    MobilePlayerSideSheet(
        visible = sheet != null,
        title = stringResource(shown.title()),
        onClose = close,
        onBack = if (shown == MobileVideoSheet.SubtitleAppearance) {
            { onSheet(MobileVideoSheet.Subtitles) }
        } else {
            null
        },
        modifier = modifier,
        panelModifier = panelModifier,
    ) {
        when (shown) {
            MobileVideoSheet.Audio -> MobileAudioSheetContent(
                player = player,
                snapshot = options,
                onAudioSelectionChanged = onAudioSelectionChanged,
                onDone = close,
            )
            MobileVideoSheet.Subtitles -> MobileSubtitlesSheetContent(
                player = player,
                snapshot = options,
                defaultTrackSelection = defaultTrackSelection,
                subtitles = playerSubtitles,
                textSize = appearance.textSize,
                offsetMillis = preferences.subtitleOffsetMillis,
                onSubtitleSelectionChanged = onSubtitleSelectionChanged,
                onAppearance = { onSheet(MobileVideoSheet.SubtitleAppearance) },
                onDone = close,
            )
            MobileVideoSheet.SubtitleAppearance -> MobileSubtitleAppearanceContent(
                textSize = appearance.textSize,
                offsetMillis = preferences.subtitleOffsetMillis,
                onTextSize = { size ->
                    val previous = appearance.textSize
                    appearance.update(size)
                    player.redrawAss(context, previous, size)
                },
                onOffset = {
                    preferences.subtitleOffsetMillis =
                        it.coerceIn(-SUBTITLE_OFFSET_LIMIT_MILLIS, SUBTITLE_OFFSET_LIMIT_MILLIS)
                },
            )
            MobileVideoSheet.Speed -> MobileSpeedSheetContent(player, options)
            MobileVideoSheet.Stream -> MobileStreamSheetContent(stream?.current) { chosen ->
                close()
                if (chosen != stream?.current) stream?.switch?.invoke()
            }
        }
    }
}

@StringRes
private fun MobileVideoSheet.title(): Int =
    when (this) {
        MobileVideoSheet.Audio -> R.string.mobile_playback_audio_control
        MobileVideoSheet.Subtitles -> R.string.mobile_playback_subtitles_title
        MobileVideoSheet.SubtitleAppearance -> PlaybackR.string.playback_size_and_timing
        MobileVideoSheet.Speed -> R.string.mobile_playback_speed
        MobileVideoSheet.Stream -> PlaybackR.string.playback_stream
    }

@Composable
private fun MobileStreamSheetContent(current: PlaybackStream?, onChoose: (PlaybackStream) -> Unit) {
    Column(Modifier.selectableGroup()) {
        PlaybackStream.entries.forEach { stream ->
            MobileSideSheetOption(
                label = stringResource(stream.label),
                selected = stream == current,
                supporting = { Text(stringResource(stream.detail)) },
                onClick = { onChoose(stream) },
            )
        }
    }
}
