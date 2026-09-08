package io.putdotio.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player as Media3Player

internal val MOBILE_PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun RetainedPlayerPreferences.adoptPlaybackOptions(player: Media3Player) {
    playbackSpeed = player.playbackParameters.speed
    val parameters = player.trackSelectionParameters
    val audioOverride = parameters.overrides.values.singleOrNull { it.type == C.TRACK_TYPE_AUDIO }
    // A reprepare can temporarily expose no current tracks; the override still owns the chosen format.
    val identity = audioOverride?.let { selected ->
        selected.trackIndices.singleOrNull()?.let { index ->
            selected.mediaTrackGroup.getFormat(index).toAudioTrackIdentity()
        }
    }
    audioSelection = identity?.let { AudioSelection.Track(it) } ?: AudioSelection.Automatic
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobilePlaybackOptions(
    player: Media3Player,
    onAudioSelectionChanged: (AudioSelection) -> Unit,
    onMenuVisibilityChanged: (Boolean) -> Unit,
    onKeyboardNavigation: () -> Unit,
    onPointerNavigation: () -> Unit,
    directControls: Boolean = false,
) {
    var speed by remember(player) { mutableStateOf(player.playbackParameters.speed) }
    var tracks by remember(player) { mutableStateOf(player.currentTracks.mobileAudioTracks()) }
    var parameters by remember(player) { mutableStateOf(player.trackSelectionParameters) }
    var commands by remember(player) { mutableStateOf(player.availableCommands) }
    var expanded by remember(player) { mutableStateOf(false) }
    var page by remember(player) { mutableStateOf(PlaybackOptionsPage.Root) }
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
    fun open(nextPage: PlaybackOptionsPage) {
        page = nextPage
        expanded = true
        onMenuVisibilityChanged(true)
    }
    if (directControls) {
        MobileVideoOptionButton(
            label = stringResource(R.string.mobile_playback_audio_control),
            icon = R.drawable.ic_ph_speaker_high,
            onClick = { open(PlaybackOptionsPage.Audio) },
            modifier = interactionModifier,
        )
        val speedLabel = playbackSpeedLabel(speed)
        val speedDescription = stringResource(R.string.mobile_playback_speed_description, speedLabel)
        MobileVideoOptionButton(
            label = stringResource(R.string.mobile_playback_speed_control, speedLabel),
            icon = R.drawable.ic_ph_gauge,
            onClick = { open(PlaybackOptionsPage.Speed) },
            modifier = interactionModifier.semantics {
                contentDescription = speedDescription
            },
        )
    } else {
        IconButton(onClick = { open(PlaybackOptionsPage.Root) }, modifier = interactionModifier) {
            Icon(
                painterResource(R.drawable.ic_ph_gear),
                contentDescription = stringResource(R.string.mobile_playback_options),
            )
        }
    }
    if (!expanded) return
    val selectedAudio = tracks.singleOrNull { track ->
        parameters.overrides[track.group]?.trackIndices?.contains(track.trackIndex) == true
    }
    val audioLabels = audioOptionLabels(tracks)
    val automaticAudio = parameters.overrides.values.none { it.type == C.TRACK_TYPE_AUDIO }
    val canChangeSpeed = commands.contains(Media3Player.COMMAND_SET_SPEED_AND_PITCH)
    val canSelectAudio = commands.contains(Media3Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = interactionModifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                if (!directControls && page != PlaybackOptionsPage.Root) {
                    IconButton(onClick = { page = PlaybackOptionsPage.Root }) {
                        Icon(
                            painterResource(R.drawable.ic_ph_arrow_left),
                            contentDescription = stringResource(R.string.mobile_action_back),
                        )
                    }
                }
                Text(
                    stringResource(when (page) {
                        PlaybackOptionsPage.Root -> R.string.mobile_playback_options
                        PlaybackOptionsPage.Speed -> R.string.mobile_playback_speed
                        PlaybackOptionsPage.Audio -> R.string.mobile_playback_audio_track
                    }),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 12.dp).semantics { heading() },
                )
            }
            when (page) {
                PlaybackOptionsPage.Root -> {
                    PlaybackSettingsRow(
                        label = stringResource(R.string.mobile_playback_speed),
                        value = playbackSpeedLabel(speed),
                        enabled = canChangeSpeed,
                        onClick = { page = PlaybackOptionsPage.Speed },
                    )
                    if (tracks.size > 1) {
                        PlaybackSettingsRow(
                            label = stringResource(R.string.mobile_playback_audio_track),
                            value = selectedAudio?.let { audioLabels[tracks.indexOf(it)] }
                                ?: stringResource(R.string.mobile_playback_audio_automatic),
                            enabled = canSelectAudio,
                            onClick = { page = PlaybackOptionsPage.Audio },
                        )
                    }
                }
                PlaybackOptionsPage.Speed -> Column(Modifier.selectableGroup()) {
                    MOBILE_PLAYBACK_SPEEDS.forEach { choice ->
                        PlaybackOption(
                            label = playbackSpeedLabel(choice),
                            selected = speed == choice,
                            enabled = canChangeSpeed,
                            onClick = {
                                player.setPlaybackSpeed(choice)
                                dismiss()
                            },
                        )
                    }
                }
                PlaybackOptionsPage.Audio -> Column(Modifier.selectableGroup()) {
                    PlaybackOption(
                        label = stringResource(R.string.mobile_playback_audio_automatic),
                        selected = automaticAudio,
                        enabled = canSelectAudio,
                        onClick = {
                            onAudioSelectionChanged(AudioSelection.Automatic)
                            player.trackSelectionParameters = parameters.withAudioSelection(
                                AudioSelection.Automatic,
                                tracks,
                            )
                            dismiss()
                        },
                    )
                    tracks.forEachIndexed { index, track ->
                        PlaybackOption(
                            label = audioLabels[index],
                            selected = track.selected && track == selectedAudio,
                            enabled = canSelectAudio,
                            onClick = {
                                val selection = AudioSelection.Track(track.identity)
                                onAudioSelectionChanged(selection)
                                player.trackSelectionParameters = parameters.withAudioTrack(track)
                                dismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun audioOptionLabels(tracks: List<MobileAudioTrack>): List<String> {
    val labels = tracks.mapIndexed { index, track ->
        track.label ?: stringResource(R.string.mobile_playback_audio_track_number, index + 1)
    }
    val automatic = stringResource(R.string.mobile_playback_audio_automatic)
    val needsNumbers = labels.distinct().size != labels.size || automatic in labels
    return labels.mapIndexed { index, label ->
        if (needsNumbers) {
            stringResource(R.string.mobile_playback_audio_track_disambiguated, label, index + 1)
        } else {
            label
        }
    }
}

private enum class PlaybackOptionsPage { Root, Speed, Audio }

@Composable
private fun playbackSpeedLabel(speed: Float): String =
    stringResource(R.string.mobile_playback_speed_value, speed.toString().removeSuffix(".0"))

@Composable
private fun PlaybackSettingsRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        supportingContent = { Text(value, style = MaterialTheme.typography.bodyMedium) },
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f),
    )
}

@Composable
internal fun PlaybackOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (selected) Icon(
            painterResource(R.drawable.ic_ph_check),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
