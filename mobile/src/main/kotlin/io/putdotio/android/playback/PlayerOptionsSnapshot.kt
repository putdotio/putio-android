package io.putdotio.android.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi

@Stable
internal class PlayerOptionsSnapshot(player: Media3Player) {
    var speed by mutableStateOf(player.playbackParameters.speed)
    var audioTracks by mutableStateOf(player.currentTracks.playbackAudioTracks())
    var subtitleTracks by mutableStateOf(player.currentTracks.playbackSubtitleTracks())
    var parameters by mutableStateOf(player.trackSelectionParameters)
    var commands by mutableStateOf(player.availableCommands)

    fun update(player: Media3Player) {
        speed = player.playbackParameters.speed
        audioTracks = player.currentTracks.playbackAudioTracks()
        subtitleTracks = player.currentTracks.playbackSubtitleTracks()
        parameters = player.trackSelectionParameters
        commands = player.availableCommands
    }

    val canChangeSpeed: Boolean get() = commands.contains(Media3Player.COMMAND_SET_SPEED_AND_PITCH)
    val canSelectTracks: Boolean get() = commands.contains(Media3Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)

    val currentAudio: PlaybackAudioTrack?
        get() = audioTracks.singleOrNull { track ->
            parameters.overrides[track.group]?.trackIndices?.contains(track.trackIndex) == true
        } ?: audioTracks.firstOrNull { it.selected }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun rememberPlayerOptionsSnapshot(player: Media3Player): PlayerOptionsSnapshot {
    val snapshot = remember(player) { PlayerOptionsSnapshot(player) }
    DisposableEffect(player) {
        val listener = object : Media3Player.Listener {
            override fun onEvents(player: Media3Player, events: Media3Player.Events) = snapshot.update(player)
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return snapshot
}
