package io.putdotio.android.tv.player

internal const val TV_SKIP_MILLIS = 10_000L

internal const val TV_SKIP_HOLD_STEP_MILLIS = 250L

internal const val TV_SKIP_HOLD_STAGE_MILLIS = 2_000L

private const val TV_SKIP_HOLD_LAST_STAGE = 2L

internal enum class TvSkipDirection { Backward, Forward }

internal data class TvSkipPress(
    val direction: TvSkipDirection,
    val downAtMillis: Long,
    val atMillis: Long,
    val repeat: Boolean,
)

internal data class TvSkipHold(
    val direction: TvSkipDirection,
    val downAtMillis: Long,
    val lastStepAtMillis: Long,
)

internal val TvSkipHold.accelerating: Boolean
    get() = lastStepAtMillis > downAtMillis

internal fun TvPlayerOverlay.skip(press: TvSkipPress, positionMillis: Long, durationMillis: Long): TvPlayerTransition {
    if (exited || durationMillis <= 0L || tooSoonToStep(press)) return TvPlayerTransition(this)
    val rate = if (press.repeat) holdRate(press.atMillis - press.downAtMillis) else 1L
    val stepMillis = TV_SKIP_MILLIS * rate
    return TvPlayerTransition(
        overlay = copy(
            controlsVisible = true,
            activity = activity + 1,
            focus = TvPlayerControl.SeekBar,
            skipHold = TvSkipHold(press.direction, press.downAtMillis, press.atMillis),
        ),
        commands = listOf(
            TvPlayerCommand.SeekTo(press.direction.step(positionMillis, stepMillis).coerceIn(0L, durationMillis)),
        ),
    )
}

internal fun TvPlayerOverlay.skipReleased(): TvPlayerTransition? =
    skipHold?.let { TvPlayerTransition(copy(skipHold = null)) }

internal fun TvPlayerOverlay.skip(forward: Boolean, positionMillis: Long, durationMillis: Long): TvPlayerTransition {
    if (exited || durationMillis <= 0L) return TvPlayerTransition(this)
    val direction = if (forward) TvSkipDirection.Forward else TvSkipDirection.Backward
    return TvPlayerTransition(
        copy(controlsVisible = true, activity = activity + 1),
        listOf(TvPlayerCommand.SeekTo(direction.step(positionMillis, TV_SKIP_MILLIS).coerceIn(0L, durationMillis))),
    )
}

internal fun TvPlayerOverlay.soughtElsewhere(): TvPlayerTransition =
    if (upNextPending) {
        TvPlayerTransition(copy(upNext = false, awaitingUpNext = false, activity = activity + 1))
    } else {
        TvPlayerTransition(this)
    }

private fun TvPlayerOverlay.tooSoonToStep(press: TvSkipPress): Boolean {
    val held = skipHold?.takeIf { press.repeat && it.holds(press) } ?: return false
    return press.atMillis - held.lastStepAtMillis < TV_SKIP_HOLD_STEP_MILLIS
}

private fun TvSkipHold.holds(press: TvSkipPress): Boolean =
    direction == press.direction && downAtMillis == press.downAtMillis

private fun holdRate(heldMillis: Long): Long {
    val stage = (heldMillis.coerceAtLeast(0L) / TV_SKIP_HOLD_STAGE_MILLIS).coerceAtMost(TV_SKIP_HOLD_LAST_STAGE)
    return 2L shl stage.toInt()
}

private fun TvSkipDirection.step(fromMillis: Long, byMillis: Long): Long =
    when (this) {
        TvSkipDirection.Backward -> fromMillis - byMillis
        TvSkipDirection.Forward -> fromMillis + byMillis
    }
