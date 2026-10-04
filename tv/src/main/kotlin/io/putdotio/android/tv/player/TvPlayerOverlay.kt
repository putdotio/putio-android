package io.putdotio.android.tv.player

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

    /** Hand over to the next video in the folder; the screen exits when there is none to find. */
    data object PlayNext : TvPlayerCommand
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

/** Shown controls that neither seek mode nor an open picker holds up; while playing they time out. */
internal val TvPlayerOverlay.autoHides: Boolean
    get() = controlsVisible && scrub == null && picker == null

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
 * The system's controls asked the media session to play or pause. Play is the dedicated play
 * key's. A pause keeps a pending scrub's target but no longer resumes on Back: a scrub already
 * holds playback paused, so the player alone would not report it.
 */
internal fun TvPlayerOverlay.sessionPlaying(play: Boolean): TvPlayerTransition =
    when {
        exited -> TvPlayerTransition(this)
        play -> setPlaying(play = true)
        else -> TvPlayerTransition(
            copy(controlsVisible = true, activity = activity + 1, scrub = scrub?.copy(wasPlaying = false)),
            listOf(TvPlayerCommand.Pause),
        )
    }

/**
 * Playback started or stopped, whoever asked. A pause shows the paused controls; playing drops a
 * pending scrub, whose target no longer means anything.
 */
internal fun TvPlayerOverlay.playingChanged(playing: Boolean): TvPlayerTransition =
    when {
        exited -> TvPlayerTransition(this)
        playing -> TvPlayerTransition(copy(scrub = null))
        else -> reveal()
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

/**
 * Playback reached the end: leave once, however many signals arrive, or with [autoplayNext]
 * move on to the next video instead.
 */
internal fun TvPlayerOverlay.ended(autoplayNext: Boolean = false): TvPlayerTransition =
    when {
        exited -> TvPlayerTransition(this)
        autoplayNext -> TvPlayerTransition(
            copy(exited = true, scrub = null, picker = null),
            listOf(TvPlayerCommand.PlayNext),
        )
        else -> exit()
    }

private fun TvPlayerOverlay.hidden() = copy(controlsVisible = false, focus = TvPlayerControl.SeekBar)

private fun TvPlayerOverlay.exit() =
    TvPlayerTransition(copy(exited = true, scrub = null, picker = null), listOf(TvPlayerCommand.Exit))
