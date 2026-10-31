package io.putdotio.android.tv.player

internal data class TvPlayerOverlay(
    val controlsVisible: Boolean = true,
    val scrub: TvScrub? = null,
    val activity: Int = 0,
    val exited: Boolean = false,
    val focus: TvPlayerControl = TvPlayerControl.PlayPause,
    val sheet: TvPlayerSheet? = null,
)

internal enum class TvPlayerSheet { Audio, Subtitles, Speed, SizeAndTiming }

internal sealed interface TvPlayerCommand {
    data object Play : TvPlayerCommand

    data object Pause : TvPlayerCommand

    data class SeekTo(val positionMillis: Long) : TvPlayerCommand

    data object Exit : TvPlayerCommand

    data object PlayNext : TvPlayerCommand
}

internal data class TvPlayerTransition(
    val overlay: TvPlayerOverlay,
    val commands: List<TvPlayerCommand> = emptyList(),
)

internal fun TvPlayerOverlay.reveal(): TvPlayerTransition =
    TvPlayerTransition(copy(controlsVisible = true, activity = activity + 1))

internal fun TvPlayerOverlay.hideTimedOut(): TvPlayerTransition =
    TvPlayerTransition(if (scrub == null && sheet == null) hidden() else this)

internal val TvPlayerOverlay.autoHides: Boolean
    get() = controlsVisible && scrub == null && sheet == null

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

internal fun TvPlayerOverlay.setPlaying(play: Boolean): TvPlayerTransition {
    if (exited) return TvPlayerTransition(this)
    return TvPlayerTransition(
        copy(scrub = null),
        listOf(if (play) TvPlayerCommand.Play else TvPlayerCommand.Pause),
    )
}

internal fun TvPlayerOverlay.sessionPlaying(play: Boolean): TvPlayerTransition =
    when {
        exited -> TvPlayerTransition(this)
        play -> setPlaying(play = true)
        else -> TvPlayerTransition(
            copy(controlsVisible = true, activity = activity + 1, scrub = scrub?.copy(wasPlaying = false)),
            listOf(TvPlayerCommand.Pause),
        )
    }

internal fun TvPlayerOverlay.playingChanged(playing: Boolean): TvPlayerTransition =
    when {
        exited -> TvPlayerTransition(this)
        playing -> TvPlayerTransition(copy(scrub = null))
        else -> reveal()
    }

internal fun TvPlayerOverlay.back(): TvPlayerTransition {
    val pending = scrub
    return when {
        exited -> TvPlayerTransition(this)
        sheet == TvPlayerSheet.SizeAndTiming ->
            TvPlayerTransition(copy(sheet = TvPlayerSheet.Subtitles, activity = activity + 1))
        sheet != null -> closeSheet()
        pending != null -> TvPlayerTransition(
            copy(scrub = null, activity = activity + 1),
            if (pending.wasPlaying) listOf(TvPlayerCommand.Play) else emptyList(),
        )
        controlsVisible -> TvPlayerTransition(hidden())
        else -> exit()
    }
}

internal fun TvPlayerOverlay.ended(autoplayNext: Boolean = false): TvPlayerTransition =
    when {
        exited -> TvPlayerTransition(this)
        autoplayNext -> playNext()
        else -> exit()
    }

private fun TvPlayerOverlay.hidden() = copy(controlsVisible = false, focus = TvPlayerControl.PlayPause)

private fun TvPlayerOverlay.exit() =
    TvPlayerTransition(copy(exited = true, scrub = null, sheet = null), listOf(TvPlayerCommand.Exit))
