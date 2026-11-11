package io.putdotio.android.tv.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.media3.common.C
import androidx.media3.common.Player

private val TV_PLAYER_KEYS = setOf(
    Key.DirectionCenter,
    Key.Enter,
    Key.NumPadEnter,
    Key.DirectionUp,
    Key.DirectionDown,
    Key.DirectionLeft,
    Key.DirectionRight,
    Key.MediaPlayPause,
    Key.MediaPlay,
    Key.MediaPause,
    Key.MediaFastForward,
    Key.MediaRewind,
)

private val TV_QUEUE_KEYS = setOf(Key.MediaNext, Key.MediaPrevious)

internal fun TvPlayerOverlay.onPlayerKey(
    event: KeyEvent,
    player: Player,
    chips: List<TvPlayerControl>,
    nextTrack: Boolean,
    apply: (TvPlayerTransition) -> Unit,
): Boolean {
    val handled = event.key in TV_PLAYER_KEYS || (nowPlaying && event.key in TV_QUEUE_KEYS)
    if (event.key == Key.Back || !handled) return false
    val focused = if (controlsVisible) focusIn(chips, nextTrack) else TvPlayerControl.SeekBar
    if (event.type == KeyEventType.KeyDown) {
        apply(keyDown(event, player, focused, chips, nextTrack))
    } else if (event.type == KeyEventType.KeyUp) {
        keyUp(event.key, player, focused)?.let(apply)
    }
    return true
}

private fun TvPlayerOverlay.keyDown(
    event: KeyEvent,
    player: Player,
    focused: TvPlayerControl,
    chips: List<TvPlayerControl>,
    nextTrack: Boolean,
): TvPlayerTransition {
    val direction = event.key.skipDirection(onSeekBar = focused == TvPlayerControl.SeekBar)
    val move = event.key.focusMove()
    return when {
        direction != null && player.canSeek() ->
            skip(
                press = TvSkipPress(
                    direction = direction,
                    downAtMillis = event.nativeKeyEvent.downTime,
                    atMillis = event.nativeKeyEvent.eventTime,
                    repeat = event.nativeKeyEvent.repeatCount > 0,
                ),
                positionMillis = player.currentPosition.coerceAtLeast(0L),
                durationMillis = player.duration,
            )
        move != null -> moveFocus(move, chips, nextTrack)
        else -> reveal()
    }
}

private fun TvPlayerOverlay.keyUp(key: Key, player: Player, focused: TvPlayerControl): TvPlayerTransition? =
    when (key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when (focused) {
            TvPlayerControl.SeekBar, TvPlayerControl.PlayPause -> select(player.playWhenReady)
            TvPlayerControl.Back, TvPlayerControl.Forward ->
                if (player.canSeek()) {
                    skip(
                        forward = focused == TvPlayerControl.Forward,
                        positionMillis = player.currentPosition.coerceAtLeast(0L),
                        durationMillis = player.duration,
                    )
                } else {
                    reveal()
                }
            TvPlayerControl.NextEpisode -> chooseNext()
            TvPlayerControl.Previous, TvPlayerControl.Next -> skipTrack(forward = focused == TvPlayerControl.Next)
            else -> openSheet(focused)
        }
        Key.MediaPlayPause -> select(player.playWhenReady)
        Key.MediaPlay -> setPlaying(play = true)
        Key.MediaPause -> setPlaying(play = false)
        Key.MediaPrevious, Key.MediaNext -> skipTrack(forward = key == Key.MediaNext)
        Key.DirectionLeft, Key.DirectionRight, Key.MediaRewind, Key.MediaFastForward -> skipReleased()
        else -> null
    }

private fun Key.skipDirection(onSeekBar: Boolean): TvSkipDirection? =
    when (this) {
        Key.MediaRewind -> TvSkipDirection.Backward
        Key.MediaFastForward -> TvSkipDirection.Forward
        Key.DirectionLeft -> TvSkipDirection.Backward.takeIf { onSeekBar }
        Key.DirectionRight -> TvSkipDirection.Forward.takeIf { onSeekBar }
        else -> null
    }

private fun Key.focusMove(): TvFocusMove? =
    when (this) {
        Key.DirectionUp -> TvFocusMove.Up
        Key.DirectionDown -> TvFocusMove.Down
        Key.DirectionLeft -> TvFocusMove.Left
        Key.DirectionRight -> TvFocusMove.Right
        else -> null
    }

private fun Player.canSeek(): Boolean =
    isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
        isCurrentMediaItemSeekable &&
        duration != C.TIME_UNSET &&
        duration > 0L
