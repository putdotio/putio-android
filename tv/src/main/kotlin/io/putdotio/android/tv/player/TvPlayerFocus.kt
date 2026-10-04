package io.putdotio.android.tv.player

/**
 * What the overlay's D-pad focus is on: the seek bar, or one of the option buttons above it
 * (tv-native `VideoPlayer.android.tsx`: Language, Subtitles, Speed, left to right).
 */
internal enum class TvPlayerControl { SeekBar, Language, Subtitles, Speed }

/** A D-pad move between the overlay's controls. */
internal enum class TvFocusMove { Up, Down, Left, Right }

/**
 * Up from the seek bar reaches the first option button, Down returns to the seek bar, and
 * Left and Right walk the [buttons] shown. Hidden controls only come back, and seek mode keeps
 * the seek bar. A focused button that is no longer shown counts as the seek bar.
 */
internal fun TvPlayerOverlay.moveFocus(move: TvFocusMove, buttons: List<TvPlayerControl>): TvPlayerTransition {
    val revealed = reveal().overlay
    if (!controlsVisible || exited || scrub != null) return TvPlayerTransition(revealed)
    val current = focusIn(buttons)
    val index = buttons.indexOf(current)
    val next = when (move) {
        TvFocusMove.Up -> if (current == TvPlayerControl.SeekBar) buttons.firstOrNull() else null
        TvFocusMove.Down -> TvPlayerControl.SeekBar
        TvFocusMove.Left -> buttons.getOrNull(index - 1).takeIf { index > 0 }
        TvFocusMove.Right -> buttons.getOrNull(index + 1).takeIf { index >= 0 }
    } ?: current
    return TvPlayerTransition(revealed.copy(focus = next))
}

/** The focused control, falling back to the seek bar when its button is not shown. */
internal fun TvPlayerOverlay.focusIn(buttons: List<TvPlayerControl>): TvPlayerControl =
    focus.takeIf { it == TvPlayerControl.SeekBar || it in buttons } ?: TvPlayerControl.SeekBar

/** Center on a focused option button opens its picker above the controls. */
internal fun TvPlayerOverlay.openPicker(button: TvPlayerControl): TvPlayerTransition =
    if (exited || button == TvPlayerControl.SeekBar) {
        TvPlayerTransition(this)
    } else {
        TvPlayerTransition(copy(controlsVisible = true, picker = button, focus = button, activity = activity + 1))
    }

/** A choice was made or the picker was dismissed: focus stays on the button that opened it. */
internal fun TvPlayerOverlay.closePicker(): TvPlayerTransition =
    TvPlayerTransition(copy(picker = null, activity = activity + 1))
