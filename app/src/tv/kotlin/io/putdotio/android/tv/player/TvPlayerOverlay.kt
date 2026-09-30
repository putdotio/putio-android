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
 * The player's dismissible layers, topmost first: seek mode, then the controls. Back
 * dismisses the topmost one and only exits once nothing is left; track pickers will stack
 * above seek mode. The resume dialog comes before the player, so it is not a layer here.
 */
internal data class TvPlayerOverlay(
    val controlsVisible: Boolean = true,
    val scrub: TvScrub? = null,
    /** Bumped on every reveal so the auto-hide timer restarts. */
    val activity: Int = 0,
    val exited: Boolean = false,
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

/** The auto-hide timer ran out; seek mode keeps the controls up. */
internal fun TvPlayerOverlay.hideTimedOut(): TvPlayerTransition =
    TvPlayerTransition(if (scrub == null) copy(controlsVisible = false) else this)

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
 * Back dismisses the topmost layer: seek mode (the position never moved; playback resumes only
 * if the scrub paused it), then the controls (pause state untouched). With nothing open it
 * exits once; Backs that arrive before the player leaves are ignored.
 */
internal fun TvPlayerOverlay.back(): TvPlayerTransition {
    val pending = scrub
    return when {
        exited -> TvPlayerTransition(this)
        pending != null -> TvPlayerTransition(
            copy(scrub = null, activity = activity + 1),
            if (pending.wasPlaying) listOf(TvPlayerCommand.Play) else emptyList(),
        )
        controlsVisible -> TvPlayerTransition(copy(controlsVisible = false))
        else -> exit()
    }
}

/** Playback reached the end: leave once, however many signals arrive. */
internal fun TvPlayerOverlay.ended(): TvPlayerTransition = if (exited) TvPlayerTransition(this) else exit()

private fun TvPlayerOverlay.exit() =
    TvPlayerTransition(copy(exited = true, scrub = null), listOf(TvPlayerCommand.Exit))
