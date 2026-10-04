package io.putdotio.android.tv.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.media3.common.C
import androidx.media3.common.Player

/** Keys the player handles itself: the D-pad (so focus stays put), play/pause and scrubbing. */
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

/**
 * Hands [event] to the overlay and [apply]s its transition; returns whether the player consumed
 * the key. Back belongs to the BackHandler; everything else is the player's.
 */
internal fun TvPlayerOverlay.onPlayerKey(
    event: KeyEvent,
    player: Player,
    buttons: List<TvPlayerControl>,
    apply: (TvPlayerTransition) -> Unit,
): Boolean {
    if (event.key == Key.Back || event.key !in TV_PLAYER_KEYS) return false
    val focused = if (controlsVisible) focusIn(buttons) else TvPlayerControl.SeekBar
    if (event.type == KeyEventType.KeyDown) {
        apply(keyDown(event, player, focused, buttons))
    } else if (event.type == KeyEventType.KeyUp) {
        keyUp(event.key, player, focused)?.let(apply)
    }
    return true
}

private fun TvPlayerOverlay.keyDown(
    event: KeyEvent,
    player: Player,
    focused: TvPlayerControl,
    buttons: List<TvPlayerControl>,
): TvPlayerTransition {
    val direction = event.key.scrubDirection(onSeekBar = focused == TvPlayerControl.SeekBar)
    val move = event.key.focusMove()
    return when {
        direction != null && player.canScrub() ->
            scrub(
                press = TvScrubPress(
                    direction = direction,
                    atMillis = event.nativeKeyEvent.eventTime,
                    repeat = event.nativeKeyEvent.repeatCount > 0,
                ),
                positionMillis = player.currentPosition.coerceAtLeast(0L),
                durationMillis = player.duration,
                playing = player.playWhenReady,
            )
        move != null -> moveFocus(move, buttons)
        else -> reveal()
    }
}

private fun TvPlayerOverlay.keyUp(key: Key, player: Player, focused: TvPlayerControl): TvPlayerTransition? =
    when (key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
            if (focused == TvPlayerControl.SeekBar) select(player.playWhenReady) else openPicker(focused)
        Key.MediaPlayPause -> select(player.playWhenReady)
        Key.MediaPlay -> setPlaying(play = true)
        Key.MediaPause -> setPlaying(play = false)
        else -> null
    }

/**
 * Rewind and fast-forward scrub from anywhere, pulling focus back to the seek bar, as the RN
 * player's did; Left and Right scrub only on the seek bar and move between buttons otherwise.
 */
private fun Key.scrubDirection(onSeekBar: Boolean): TvScrubDirection? =
    when (this) {
        Key.MediaRewind -> TvScrubDirection.Backward
        Key.MediaFastForward -> TvScrubDirection.Forward
        Key.DirectionLeft -> TvScrubDirection.Backward.takeIf { onSeekBar }
        Key.DirectionRight -> TvScrubDirection.Forward.takeIf { onSeekBar }
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

private fun Player.canScrub(): Boolean =
    isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
        isCurrentMediaItemSeekable &&
        duration != C.TIME_UNSET &&
        duration > 0L
