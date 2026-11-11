package io.putdotio.android.tv.player

internal const val TV_SKIP_MILLIS = 10_000L

internal const val TV_SKIP_HOLD_STEP_MILLIS = 250L

internal const val TV_SKIP_HOLD_STAGE_MILLIS = 2_000L

internal const val TV_SKIP_HOLD_END_GAP_MILLIS = 1_000L

private const val TV_SKIP_HOLD_STAGES = 3

internal val TV_SKIP_HOLD_SPEEDS = List(TV_SKIP_HOLD_STAGES) { stage -> 2L shl stage }

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
    val startAtMillis: Long,
    val originMillis: Long,
    val lastStepAtMillis: Long,
)

internal val TvSkipHold.accelerating: Boolean
    get() = lastStepAtMillis > startAtMillis

internal fun TvPlayerOverlay.skip(press: TvSkipPress, positionMillis: Long, durationMillis: Long): TvPlayerTransition {
    val held = skipHold?.takeIf { press.repeat && it.holds(press) }
    return when {
        exited || durationMillis <= 0L -> TvPlayerTransition(this)
        held != null -> held.step(press.atMillis, durationMillis)?.let { stepped ->
            shownWith(stepped, TvPlayerCommand.SeekTo(stepped.positionAt(press.atMillis, durationMillis)))
        } ?: TvPlayerTransition(this)
        press.repeat -> shownWith(
            TvSkipHold(press.direction, press.downAtMillis, press.atMillis, positionMillis, press.atMillis),
            command = null,
        )
        else -> {
            val target = press.direction.step(positionMillis, TV_SKIP_MILLIS).coerceIn(0L, durationMillis)
            shownWith(
                TvSkipHold(press.direction, press.downAtMillis, press.atMillis, target, press.atMillis),
                TvPlayerCommand.SeekTo(target),
            )
        }
    }
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

private fun holdTravelMillis(heldMillis: Long): Long {
    val held = heldMillis.coerceAtLeast(0L)
    return TV_SKIP_HOLD_SPEEDS.withIndex().sumOf { (stage, speed) ->
        val stageStart = stage * TV_SKIP_HOLD_STAGE_MILLIS
        val stageEnd = stageStart + TV_SKIP_HOLD_STAGE_MILLIS
        val until = if (stage == TV_SKIP_HOLD_SPEEDS.lastIndex) held else held.coerceAtMost(stageEnd)
        (until - stageStart).coerceAtLeast(0L) * speed
    }
}

private fun TvPlayerOverlay.shownWith(hold: TvSkipHold, command: TvPlayerCommand?) = TvPlayerTransition(
    overlay = copy(
        controlsVisible = true,
        activity = activity + 1,
        focus = TvPlayerControl.SeekBar,
        skipHold = hold,
    ),
    commands = listOfNotNull(command),
)

private fun TvSkipHold.holds(press: TvSkipPress): Boolean =
    direction == press.direction && downAtMillis == press.downAtMillis

private fun TvSkipHold.step(atMillis: Long, durationMillis: Long): TvSkipHold? =
    copy(lastStepAtMillis = atMillis).takeIf {
        atMillis - lastStepAtMillis >= TV_SKIP_HOLD_STEP_MILLIS &&
            (direction == TvSkipDirection.Backward || originMillis < durationMillis)
    }

private fun TvSkipHold.positionAt(atMillis: Long, durationMillis: Long): Long =
    direction.step(originMillis, holdTravelMillis(atMillis - startAtMillis))
        .coerceIn(0L, maxOf(originMillis, durationMillis - TV_SKIP_HOLD_END_GAP_MILLIS))

private fun TvSkipDirection.step(fromMillis: Long, byMillis: Long): Long =
    when (this) {
        TvSkipDirection.Backward -> fromMillis - byMillis
        TvSkipDirection.Forward -> fromMillis + byMillis
    }
