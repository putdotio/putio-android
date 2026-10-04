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

/** One Left, Right, rewind or fast-forward key-down, at [atMillis]; [repeat] when the key is held. */
internal data class TvScrubPress(
    val direction: TvScrubDirection,
    val atMillis: Long,
    val repeat: Boolean,
)

/** A pending seek: the bar shows [targetMillis] and playback stays paused until a commit. */
internal data class TvScrub(
    val targetMillis: Long,
    val presses: Int,
    val lastPressAtMillis: Long,
    /** Whether playback ran before the scrub paused it, so Back can restore it. */
    val wasPlaying: Boolean,
)

/**
 * Left, Right, rewind or fast-forward. The first press pauses playback; each press moves the
 * pending target by [TV_SCRUB_STEP_MILLIS] times the press count, which grows while presses
 * come within [TV_SCRUB_ACCUMULATE_MILLIS] (or as a held key repeats) and restarts at one after a gap.
 */
internal fun TvPlayerOverlay.scrub(
    press: TvScrubPress,
    positionMillis: Long,
    durationMillis: Long,
    playing: Boolean,
): TvPlayerTransition {
    val previous = scrub
    if (exited || durationMillis <= 0L || tooSoonToRepeat(previous, press)) return TvPlayerTransition(this)
    val presses = pressCount(previous, press)
    val target = press.direction
        .step(fromMillis = previous?.targetMillis ?: positionMillis, byMillis = TV_SCRUB_STEP_MILLIS * presses)
        .coerceIn(0L, durationMillis)
    return TvPlayerTransition(
        overlay = copy(
            controlsVisible = true,
            activity = activity + 1,
            // Rewind and fast-forward pull focus back to the seek bar from an option button.
            focus = TvPlayerControl.SeekBar,
            scrub = TvScrub(
                targetMillis = target,
                presses = presses,
                lastPressAtMillis = press.atMillis,
                wasPlaying = previous?.wasPlaying ?: playing,
            ),
        ),
        commands = if (previous == null && playing) listOf(TvPlayerCommand.Pause) else emptyList(),
    )
}

/**
 * Playback moved without the overlay: the system's controls sought through the session. A pending
 * scrub's target no longer applies, so a later commit cannot undo that seek.
 */
internal fun TvPlayerOverlay.soughtElsewhere(): TvPlayerTransition =
    TvPlayerTransition(if (scrub == null) this else copy(scrub = null, activity = activity + 1))

/** A held key's repeat sooner than [TV_SCRUB_HOLD_REPEAT_MILLIS] after the last counted press. */
private fun tooSoonToRepeat(previous: TvScrub?, press: TvScrubPress): Boolean =
    press.repeat && previous != null && press.atMillis - previous.lastPressAtMillis < TV_SCRUB_HOLD_REPEAT_MILLIS

private fun pressCount(previous: TvScrub?, press: TvScrubPress): Int =
    when {
        previous == null -> 1
        press.repeat || press.atMillis - previous.lastPressAtMillis < TV_SCRUB_ACCUMULATE_MILLIS ->
            previous.presses + 1
        else -> 1
    }

private fun TvScrubDirection.step(fromMillis: Long, byMillis: Long): Long =
    when (this) {
        TvScrubDirection.Backward -> fromMillis - byMillis
        TvScrubDirection.Forward -> fromMillis + byMillis
    }
