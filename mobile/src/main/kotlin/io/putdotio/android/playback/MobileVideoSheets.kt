package io.putdotio.android.playback

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import io.putdotio.android.MobileKeyCommand
import io.putdotio.android.MobileShortcutScope
import io.putdotio.android.R
import io.putdotio.android.mobileKeyCommand
import io.putdotio.sdk.files.PlaybackSubtitle

internal val MobileVideoSheet.isSubtitles: Boolean
    get() = this == MobileVideoSheet.Subtitles || this == MobileVideoSheet.SubtitleAppearance

/** The video's option chips; hide_subtitles hides subtitles entirely, as every reference player does. */
@Composable
internal fun MobileVideoChips(
    options: PlayerOptionsSnapshot,
    subtitleStartupPolicy: SubtitleStartupPolicy?,
    onSheet: (MobileVideoSheet) -> Unit,
    modifier: Modifier,
) {
    MobileAudioChip(options, { onSheet(MobileVideoSheet.Audio) }, modifier)
    if (subtitleStartupPolicy?.showSubtitles != false) {
        MobileSubtitlesChip(options, { onSheet(MobileVideoSheet.Subtitles) }, modifier)
    }
    MobileSpeedChip(options, { onSheet(MobileVideoSheet.Speed) }, modifier)
}

/** Next episode, when the account's next-episode setting found one. */
@Composable
internal fun MobileNextEpisodeChip(nextEpisode: PlaybackTarget?, onNextEpisode: () -> Unit, modifier: Modifier) {
    if (nextEpisode == null) return
    MobilePlayerChip(
        label = stringResource(R.string.mobile_playback_next_episode),
        description = null,
        onClick = onNextEpisode,
        trailingIcon = R.drawable.ic_ph_skip_forward,
        modifier = modifier,
    )
}

/** Picture-in-picture, where the host and the device have the window. */
@Composable
internal fun MobilePictureInPictureButton(enter: (() -> Unit)?, modifier: Modifier) {
    if (enter == null) return
    IconButton(onClick = enter, modifier = modifier) {
        Icon(
            painterResource(R.drawable.ic_ph_picture_in_picture),
            contentDescription = stringResource(R.string.mobile_playback_picture_in_picture),
        )
    }
}

/**
 * The player's keys with no sheet open. With one open the keys move through it, and leaving
 * closes the sheet, not the player.
 */
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

/** Where Back goes from a sheet: Size and timing returns to Subtitles, the rest close. */
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
    sidecarSubtitles: List<PlaybackSubtitle>,
    appearance: SubtitleAppearanceStore,
    preferences: RetainedPlayerPreferences,
    onSubtitleSelectionChanged: (SubtitleSelection) -> Unit,
    modifier: Modifier,
    panelModifier: Modifier,
) {
    // The last sheet stays composed while it slides out.
    var shown by remember { mutableStateOf(sheet ?: MobileVideoSheet.Audio) }
    if (sheet != null) shown = sheet
    val close = { onSheet(null) }
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
                onAudioSelectionChanged = { preferences.audioSelection = it },
                onDone = close,
            )
            MobileVideoSheet.Subtitles -> MobileSubtitlesSheetContent(
                player = player,
                snapshot = options,
                defaultTrackSelection = defaultTrackSelection,
                sidecar = sidecarSubtitles,
                textSize = appearance.textSize,
                offsetMillis = preferences.subtitleOffsetMillis,
                onSubtitleSelectionChanged = onSubtitleSelectionChanged,
                onAppearance = { onSheet(MobileVideoSheet.SubtitleAppearance) },
                onDone = close,
            )
            MobileVideoSheet.SubtitleAppearance -> MobileSubtitleAppearanceContent(
                textSize = appearance.textSize,
                offsetMillis = preferences.subtitleOffsetMillis,
                onTextSize = appearance::update,
                onOffset = {
                    preferences.subtitleOffsetMillis =
                        it.coerceIn(-SUBTITLE_OFFSET_LIMIT_MILLIS, SUBTITLE_OFFSET_LIMIT_MILLIS)
                },
            )
            MobileVideoSheet.Speed -> MobileSpeedSheetContent(player, options)
        }
    }
}

@StringRes
private fun MobileVideoSheet.title(): Int =
    when (this) {
        MobileVideoSheet.Audio -> R.string.mobile_playback_audio_control
        MobileVideoSheet.Subtitles -> R.string.mobile_playback_subtitles_title
        MobileVideoSheet.SubtitleAppearance -> R.string.mobile_playback_subtitle_appearance
        MobileVideoSheet.Speed -> R.string.mobile_playback_speed
    }
