package io.putdotio.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player as Media3Player

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobileSubtitleControls(
    player: Media3Player,
    defaultTrackSelection: TrackSelectionParameters,
    onSubtitleSelectionChanged: (SubtitleSelection) -> Unit,
    onMenuVisibilityChanged: (Boolean) -> Unit,
    onKeyboardNavigation: () -> Unit,
    onPointerNavigation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tracks by remember(player) { mutableStateOf(player.currentTracks.mobileSubtitleTracks()) }
    var parameters by remember(player) { mutableStateOf(player.trackSelectionParameters) }
    var commands by remember(player) { mutableStateOf(player.availableCommands) }
    var expanded by remember(player) { mutableStateOf(false) }
    val currentOnMenuVisibilityChanged = rememberUpdatedState(onMenuVisibilityChanged)
    DisposableEffect(player) {
        val listener = object : Media3Player.Listener {
            override fun onEvents(player: Media3Player, events: Media3Player.Events) {
                tracks = player.currentTracks.mobileSubtitleTracks()
                parameters = player.trackSelectionParameters
                commands = player.availableCommands
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            currentOnMenuVisibilityChanged.value(false)
        }
    }
    fun dismiss() {
        expanded = false
        onMenuVisibilityChanged(false)
    }
    fun select(selection: SubtitleSelection) {
        player.trackSelectionParameters = player.trackSelectionParameters.withSubtitleSelection(
            selection = selection,
            tracks = tracks,
            textDefaults = defaultTrackSelection,
        )
        onSubtitleSelectionChanged(selection)
        dismiss()
    }
    val interactionModifier = Modifier
        .observePlayerControlInteraction(
            onInteractionChanged = { if (it) onPointerNavigation() },
            onActivity = {},
        ).observePlayerControlKeyActivity(onKeyboardNavigation)
    val description = stringResource(
        if (parameters.subtitlesEnabled(tracks)) {
            R.string.mobile_playback_subtitles_on
        } else {
            R.string.mobile_playback_subtitles_off
        },
    )
    IconButton(
        onClick = {
            expanded = true
            onMenuVisibilityChanged(true)
        },
        modifier = modifier.then(interactionModifier).semantics { stateDescription = description },
    ) {
        Icon(
            painterResource(R.drawable.ic_ph_subtitles),
            contentDescription = stringResource(R.string.mobile_playback_choose_subtitles),
        )
    }
    if (!expanded) return
    val disabled = C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes
    val automatic = !disabled && parameters.overrides.values.none { it.type == C.TRACK_TYPE_TEXT }
    val canSelect = commands.contains(Media3Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = interactionModifier.verticalScroll(rememberScrollState())
                .selectableGroup().padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.mobile_playback_subtitles_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).semantics { heading() },
            )
            PlaybackOption(
                label = stringResource(R.string.mobile_playback_subtitles_disabled),
                selected = disabled,
                enabled = canSelect,
                onClick = { select(SubtitleSelection.Off) },
                modifier = interactionModifier,
            )
            PlaybackOption(
                label = stringResource(R.string.mobile_playback_audio_automatic),
                selected = automatic,
                enabled = canSelect,
                onClick = { select(SubtitleSelection.Automatic) },
                modifier = interactionModifier,
            )
            tracks.forEach { track ->
                val selected = !disabled &&
                    parameters.overrides[track.group]?.trackIndices?.contains(track.trackIndex) == true
                MobileSubtitleTrackOption(
                    track = track.copy(selected = selected),
                    onClick = { select(SubtitleSelection.Track(track.identity)) },
                    modifier = interactionModifier,
                    enabled = canSelect,
                )
            }
        }
    }
}

@Composable
internal fun MobileSubtitleTrackOption(
    track: MobileSubtitleTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PlaybackOption(
        label = track.label ?: stringResource(R.string.mobile_playback_subtitle_track, track.trackIndex + 1),
        selected = track.selected,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    )
}
