package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player as Media3Player

internal val MOBILE_PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun RetainedPlayerPreferences.adoptPlaybackOptions(player: Media3Player) {
    playbackSpeed = player.playbackParameters.speed
    val parameters = player.trackSelectionParameters
    val explicitTrack = player.currentTracks.mobileAudioTracks().singleOrNull { track ->
        parameters.overrides[track.group]?.trackIndices?.contains(track.trackIndex) == true
    }
    audioSelection = explicitTrack?.let { AudioSelection.Track(it.identity) } ?: AudioSelection.Automatic
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobilePlaybackOptions(
    player: Media3Player,
    onAudioSelectionChanged: (AudioSelection) -> Unit,
    onMenuVisibilityChanged: (Boolean) -> Unit,
    onKeyboardNavigation: () -> Unit,
    onPointerNavigation: () -> Unit,
) {
    var speed by remember(player) { mutableStateOf(player.playbackParameters.speed) }
    var tracks by remember(player) { mutableStateOf(player.currentTracks.mobileAudioTracks()) }
    var parameters by remember(player) { mutableStateOf(player.trackSelectionParameters) }
    var commands by remember(player) { mutableStateOf(player.availableCommands) }
    var expanded by remember(player) { mutableStateOf(false) }
    val currentOnMenuVisibilityChanged = rememberUpdatedState(onMenuVisibilityChanged)
    DisposableEffect(player) {
        val listener = object : Media3Player.Listener {
            override fun onEvents(player: Media3Player, events: Media3Player.Events) {
                speed = player.playbackParameters.speed
                tracks = player.currentTracks.mobileAudioTracks()
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
    val interactionModifier = Modifier
        .observePlayerControlInteraction(
            onInteractionChanged = { if (it) onPointerNavigation() },
            onActivity = {},
        ).observePlayerControlKeyActivity(onKeyboardNavigation)
    Box {
        TextButton(onClick = {
            expanded = true
            onMenuVisibilityChanged(true)
        }) {
            Text(stringResource(R.string.mobile_playback_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = ::dismiss, modifier = interactionModifier) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_playback_speed)) },
                onClick = {},
                enabled = false,
            )
            MOBILE_PLAYBACK_SPEEDS.forEach { choice ->
                PlaybackOption(
                    label = stringResource(R.string.mobile_playback_speed_value, choice.toString().removeSuffix(".0")),
                    selected = speed == choice,
                    enabled = commands.contains(Media3Player.COMMAND_SET_SPEED_AND_PITCH),
                    modifier = interactionModifier,
                    onClick = {
                        player.setPlaybackSpeed(choice)
                        dismiss()
                    },
                )
            }
            if (tracks.size > 1) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mobile_playback_audio_track)) },
                    onClick = {},
                    enabled = false,
                )
                val canSelect = commands.contains(Media3Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
                PlaybackOption(
                    label = stringResource(R.string.mobile_playback_audio_automatic),
                    selected = parameters.overrides.values.none { it.type == C.TRACK_TYPE_AUDIO },
                    enabled = canSelect,
                    modifier = interactionModifier,
                    onClick = {
                        onAudioSelectionChanged(AudioSelection.Automatic)
                        player.trackSelectionParameters = parameters.withAudioSelection(AudioSelection.Automatic, tracks)
                        dismiss()
                    },
                )
                tracks.forEachIndexed { index, track ->
                    PlaybackOption(
                        label = track.label ?: stringResource(R.string.mobile_playback_audio_track_number, index + 1),
                        selected = track.selected &&
                            parameters.overrides[track.group]?.trackIndices?.contains(track.trackIndex) == true,
                        enabled = canSelect,
                        modifier = interactionModifier,
                        onClick = {
                            val selection = AudioSelection.Track(track.identity)
                            onAudioSelectionChanged(selection)
                            player.trackSelectionParameters = parameters.withAudioSelection(selection, tracks)
                            dismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlaybackOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = if (selected) {
            { Text(stringResource(R.string.mobile_playback_subtitle_selected)) }
        } else {
            null
        },
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics {
            role = Role.RadioButton
            toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
        },
    )
}
