package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.res.stringResource
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.Player as Media3Player

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
    var enabled by remember(player) {
        mutableStateOf(player.trackSelectionParameters.subtitlesEnabled(tracks))
    }
    var menuExpanded by remember(player) { mutableStateOf(false) }
    val currentOnMenuVisibilityChanged = rememberUpdatedState(onMenuVisibilityChanged)

    DisposableEffect(player) {
        onDispose { currentOnMenuVisibilityChanged.value(false) }
    }

    DisposableEffect(player) {
        val listener =
            object : Media3Player.Listener {
                override fun onTracksChanged(currentTracks: Tracks) {
                    tracks = currentTracks.mobileSubtitleTracks()
                    enabled = player.trackSelectionParameters.subtitlesEnabled(tracks)
                }

                override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                    tracks = player.currentTracks.mobileSubtitleTracks()
                    enabled = parameters.subtitlesEnabled(tracks)
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        MobileSubtitleToggle(
            enabled = enabled,
            onToggle = { subtitlesEnabled ->
                enabled = subtitlesEnabled
                val selection =
                    if (subtitlesEnabled) SubtitleSelection.Automatic else SubtitleSelection.Off
                val parameters =
                    player.trackSelectionParameters.withSubtitleSelection(
                        selection = selection,
                        tracks = tracks,
                        textDefaults = defaultTrackSelection,
                    )
                player.trackSelectionParameters = parameters
                onSubtitleSelectionChanged(selection)
            },
        )
        if (tracks.size > 1) {
            Box {
                TextButton(
                    onClick = {
                        menuExpanded = true
                        onMenuVisibilityChanged(true)
                    },
                ) {
                    Text(stringResource(R.string.mobile_playback_choose_subtitles))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = {
                        menuExpanded = false
                        onMenuVisibilityChanged(false)
                    },
                    modifier =
                        Modifier
                            .observePlayerControlInteraction(
                                onInteractionChanged = { if (it) onPointerNavigation() },
                                onActivity = {},
                            ).observePlayerControlKeyActivity(onKeyboardNavigation),
                ) {
                    tracks.forEach { track ->
                        MobileSubtitleTrackOption(
                            track = track,
                            modifier =
                                Modifier
                                    .observePlayerControlInteraction(
                                        onInteractionChanged = { if (it) onPointerNavigation() },
                                        onActivity = {},
                                    ).observePlayerControlKeyActivity(onKeyboardNavigation),
                            onClick = {
                                enabled = true
                                val selection = SubtitleSelection.Track(track.identity)
                                val parameters =
                                    player.trackSelectionParameters.withSubtitleSelection(
                                        selection = selection,
                                        tracks = tracks,
                                        textDefaults = defaultTrackSelection,
                                    )
                                player.trackSelectionParameters = parameters
                                onSubtitleSelectionChanged(selection)
                                menuExpanded = false
                                onMenuVisibilityChanged(false)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MobileSubtitleTrackOption(
    track: MobileSubtitleTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenuItem(
        text = {
            Text(
                track.label
                    ?: stringResource(
                        R.string.mobile_playback_subtitle_track,
                        track.trackIndex + 1,
                    ),
            )
        },
        trailingIcon =
            if (track.selected) {
                { Text(stringResource(R.string.mobile_playback_subtitle_selected)) }
            } else {
                null
            },
        onClick = onClick,
        modifier =
            modifier.semantics {
                role = Role.RadioButton
                toggleableState = if (track.selected) ToggleableState.On else ToggleableState.Off
            },
    )
}

@Composable
internal fun MobileSubtitleToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = { onToggle(!enabled) },
        modifier =
            modifier.semantics {
                role = Role.Switch
                toggleableState = if (enabled) ToggleableState.On else ToggleableState.Off
            },
    ) {
        Text(
            stringResource(
                if (enabled) R.string.mobile_playback_subtitles_on else R.string.mobile_playback_subtitles_off,
            ),
        )
    }
}
