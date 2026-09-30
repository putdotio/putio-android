package io.putdotio.android.tv.player

/** One scrub step; consecutive presses multiply it (15 s, 30 s, 45 s, …). */
internal const val TV_SCRUB_STEP_MILLIS = 15_000L

/** Presses closer together than this, or a held key's repeats, keep growing the step. */
internal const val TV_SCRUB_ACCUMULATE_MILLIS = 500L

/**
 * A held key counts one press per this interval, the cadence the RN player saw long presses
 * at; Android repeats key-down every ~50 ms, which would run the growing step away.
 */
internal const val TV_SCRUB_HOLD_REPEAT_MILLIS = 300L

internal enum class TvScrubDirection { Backward, Forward }

/** A pending seek: the bar shows [targetMillis] and playback stays paused until a commit. */
internal data class TvScrub(
    val targetMillis: Long,
    val presses: Int,
    val lastPressAtMillis: Long,
    /** Whether playback ran before the scrub paused it, so Back can restore it. */
    val wasPlaying: Boolean,
)

/**
 * What the overlay's D-pad focus is on: the seek bar, or one of the option buttons above it
 * (tv-native `VideoPlayer.android.tsx`: Language, Subtitles, Speed, left to right).
 */
internal enum class TvPlayerControl { SeekBar, Language, Subtitles, Speed }

/** A D-pad move between the overlay's controls. */
internal enum class TvFocusMove { Up, Down, Left, Right }

/**
 * The player's dismissible layers, topmost first: an open picker, seek mode, then the
 * controls. Back dismisses the topmost one and only exits once nothing is left. The resume
 * dialog comes before the player, so it is not a layer here.
 */
internal data class TvPlayerOverlay(
    val controlsVisible: Boolean = true,
    val scrub: TvScrub? = null,
    /** Bumped on every reveal so the auto-hide timer restarts. */
    val activity: Int = 0,
    val exited: Boolean = false,
    val focus: TvPlayerControl = TvPlayerControl.SeekBar,
    /** The option button whose picker is open; focus returns to it when the picker closes. */
    val picker: TvPlayerControl? = null,
)

internal sealed interface TvPlayerCommand {
    data object Play : TvPlayerCommand

    data object Pause : TvPlayerCommand

    data class SeekTo(val positionMillis: Long) : TvPlayerCommand

    data object Exit : TvPlayerCommand
}

internal data class TvPlayerTransition(
    val overlay: TvPlayerOverlay,
    val commands: List<TvPlayerCommand> = emptyList(),
)

/** Any key shows the controls and restarts the auto-hide timer. */
internal fun TvPlayerOverlay.reveal(): TvPlayerTransition =
    TvPlayerTransition(copy(controlsVisible = true, activity = activity + 1))

/**
 * The auto-hide timer ran out; seek mode and an open picker keep the controls up. Hidden
 * controls come back on the seek bar, where Left and Right scrub.
 */
internal fun TvPlayerOverlay.hideTimedOut(): TvPlayerTransition =
    TvPlayerTransition(if (scrub == null && picker == null) hidden() else this)

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

/**
 * Left, Right, rewind or fast-forward. The first press pauses playback; each press moves the
 * pending target by [TV_SCRUB_STEP_MILLIS] times the press count, which grows while presses
 * come within [TV_SCRUB_ACCUMULATE_MILLIS] (or as a held key repeats) and restarts at one after a gap.
 */
internal fun TvPlayerOverlay.scrub(
    direction: TvScrubDirection,
    positionMillis: Long,
    durationMillis: Long,
    playing: Boolean,
    nowMillis: Long,
    repeat: Boolean,
): TvPlayerTransition {
    if (exited || durationMillis <= 0L) return TvPlayerTransition(this)
    val previous = scrub
    if (repeat && previous != null && nowMillis - previous.lastPressAtMillis < TV_SCRUB_HOLD_REPEAT_MILLIS) {
        return TvPlayerTransition(this)
    }
    val presses = when {
        previous == null -> 1
        repeat || nowMillis - previous.lastPressAtMillis < TV_SCRUB_ACCUMULATE_MILLIS -> previous.presses + 1
        else -> 1
    }
    val base = previous?.targetMillis ?: positionMillis
    val step = TV_SCRUB_STEP_MILLIS * presses
    val target = when (direction) {
        TvScrubDirection.Backward -> base - step
        TvScrubDirection.Forward -> base + step
    }.coerceIn(0L, durationMillis)
    return TvPlayerTransition(
        overlay = copy(
            controlsVisible = true,
            activity = activity + 1,
            // Rewind and fast-forward pull focus back to the seek bar from an option button.
            focus = TvPlayerControl.SeekBar,
            scrub = TvScrub(
                targetMillis = target,
                presses = presses,
                lastPressAtMillis = nowMillis,
                wasPlaying = previous?.wasPlaying ?: playing,
            ),
        ),
        commands = if (previous == null && playing) listOf(TvPlayerCommand.Pause) else emptyList(),
    )
}

/** Center, Enter or play/pause: commits a scrub and plays from there, otherwise toggles playback. */
internal fun TvPlayerOverlay.select(playing: Boolean): TvPlayerTransition {
    if (exited) return TvPlayerTransition(this)
    val pending = scrub
    return when {
        pending != null -> TvPlayerTransition(
            copy(scrub = null),
            listOf(TvPlayerCommand.SeekTo(pending.targetMillis), TvPlayerCommand.Play),
        )
        playing -> TvPlayerTransition(this, listOf(TvPlayerCommand.Pause))
        else -> TvPlayerTransition(this, listOf(TvPlayerCommand.Play))
    }
}

/** The remote's dedicated play or pause key: a pending scrub is dropped, not committed. */
internal fun TvPlayerOverlay.setPlaying(play: Boolean): TvPlayerTransition {
    if (exited) return TvPlayerTransition(this)
    return TvPlayerTransition(
        copy(scrub = null),
        listOf(if (play) TvPlayerCommand.Play else TvPlayerCommand.Pause),
    )
}

/**
 * Back dismisses the topmost layer: an open picker (no change; focus back on its button), seek
 * mode (the position never moved; playback resumes only if the scrub paused it), then the
 * controls (pause state untouched). With nothing open it exits once; Backs that arrive before
 * the player leaves are ignored.
 */
internal fun TvPlayerOverlay.back(): TvPlayerTransition {
    val pending = scrub
    return when {
        exited -> TvPlayerTransition(this)
        picker != null -> closePicker()
        pending != null -> TvPlayerTransition(
            copy(scrub = null, activity = activity + 1),
            if (pending.wasPlaying) listOf(TvPlayerCommand.Play) else emptyList(),
        )
        controlsVisible -> TvPlayerTransition(hidden())
        else -> exit()
    }
}

/** Playback reached the end: leave once, however many signals arrive. */
internal fun TvPlayerOverlay.ended(): TvPlayerTransition = if (exited) TvPlayerTransition(this) else exit()

private fun TvPlayerOverlay.hidden() = copy(controlsVisible = false, focus = TvPlayerControl.SeekBar)

private fun TvPlayerOverlay.exit() =
    TvPlayerTransition(copy(exited = true, scrub = null, picker = null), listOf(TvPlayerCommand.Exit))
