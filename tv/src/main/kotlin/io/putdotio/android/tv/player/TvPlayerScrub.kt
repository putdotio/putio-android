package io.putdotio.android.tv.player

internal const val TV_SCRUB_STEP_MILLIS = 15_000L

internal const val TV_SCRUB_ACCUMULATE_MILLIS = 500L

internal const val TV_SCRUB_HOLD_REPEAT_MILLIS = 300L

internal enum class TvScrubDirection { Backward, Forward }

internal data class TvScrubPress(
    val direction: TvScrubDirection,
    val atMillis: Long,
    val repeat: Boolean,
)

internal data class TvScrub(
    val targetMillis: Long,
    val presses: Int,
    val lastPressAtMillis: Long,
    val wasPlaying: Boolean,
)

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

internal fun TvPlayerOverlay.soughtElsewhere(): TvPlayerTransition =
    TvPlayerTransition(if (scrub == null) this else copy(scrub = null, activity = activity + 1))

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

internal fun TvPlayerOverlay.skip(forward: Boolean, positionMillis: Long, durationMillis: Long): TvPlayerTransition {
    if (exited || durationMillis <= 0L) return TvPlayerTransition(this)
    val step = if (forward) TV_SKIP_MILLIS else -TV_SKIP_MILLIS
    return TvPlayerTransition(
        copy(controlsVisible = true, scrub = null, activity = activity + 1),
        listOf(TvPlayerCommand.SeekTo((positionMillis + step).coerceIn(0L, durationMillis))),
    )
}

internal const val TV_SKIP_MILLIS = 10_000L
