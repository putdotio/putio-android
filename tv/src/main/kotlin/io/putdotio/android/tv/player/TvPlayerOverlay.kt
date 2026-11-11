package io.putdotio.android.tv.player

import io.putdotio.android.playback.PlaybackTarget

internal data class TvPlayerOverlay(
    val controlsVisible: Boolean = true,
    val skipHold: TvSkipHold? = null,
    val activity: Int = 0,
    val exited: Boolean = false,
    val focus: TvPlayerControl = TvPlayerControl.PlayPause,
    val sheet: TvPlayerSheet? = null,
    val backFromSizeAndTiming: Boolean = false,
    val upNext: Boolean = false,
    val awaitingUpNext: Boolean = false,
    val nowPlaying: Boolean = false,
)

internal enum class TvPlayerSheet { Audio, Subtitles, Speed, SizeAndTiming, Stream }

internal sealed interface TvPlayerCommand {
    data object Play : TvPlayerCommand

    data object Pause : TvPlayerCommand

    data class SeekTo(val positionMillis: Long) : TvPlayerCommand

    data object Exit : TvPlayerCommand

    data object Background : TvPlayerCommand

    data object PlayNext : TvPlayerCommand

    data object ChooseNext : TvPlayerCommand

    data class PlayChosen(val target: PlaybackTarget) : TvPlayerCommand

    data object PlayPrevious : TvPlayerCommand

    data object PlayFollowing : TvPlayerCommand
}

internal data class TvPlayerTransition(
    val overlay: TvPlayerOverlay,
    val commands: List<TvPlayerCommand> = emptyList(),
)

internal fun TvPlayerOverlay.reveal(): TvPlayerTransition =
    TvPlayerTransition(copy(controlsVisible = true, activity = activity + 1))

internal fun TvPlayerOverlay.hideTimedOut(): TvPlayerTransition =
    TvPlayerTransition(if (holdsControls) this else hidden())

internal val TvPlayerOverlay.autoHides: Boolean
    get() = controlsVisible && !holdsControls

private val TvPlayerOverlay.holdsControls: Boolean
    get() = sheet != null || upNextPending || nowPlaying

internal fun TvPlayerOverlay.select(playing: Boolean): TvPlayerTransition {
    if (exited) return TvPlayerTransition(this)
    return TvPlayerTransition(this, listOf(if (playing) TvPlayerCommand.Pause else TvPlayerCommand.Play))
}

internal fun TvPlayerOverlay.setPlaying(play: Boolean): TvPlayerTransition {
    if (exited) return TvPlayerTransition(this)
    return TvPlayerTransition(this, listOf(if (play) TvPlayerCommand.Play else TvPlayerCommand.Pause))
}

internal fun TvPlayerOverlay.sessionPlaying(play: Boolean): TvPlayerTransition =
    when {
        exited -> TvPlayerTransition(this)
        play -> setPlaying(play = true)
        else -> TvPlayerTransition(reveal().overlay, listOf(TvPlayerCommand.Pause))
    }

internal fun TvPlayerOverlay.playingChanged(playing: Boolean): TvPlayerTransition =
    if (exited || playing) TvPlayerTransition(this) else reveal()

internal fun TvPlayerOverlay.back(backgrounds: Boolean = false): TvPlayerTransition =
    when {
        exited -> TvPlayerTransition(this)
        upNextPending -> cancelUpNext()
        sheet == TvPlayerSheet.SizeAndTiming ->
            TvPlayerTransition(
                copy(sheet = TvPlayerSheet.Subtitles, backFromSizeAndTiming = true, activity = activity + 1),
            )
        sheet != null -> closeSheet()
        controlsVisible && !nowPlaying -> TvPlayerTransition(hidden())
        nowPlaying && backgrounds -> TvPlayerTransition(this, listOf(TvPlayerCommand.Background))
        else -> exit()
    }

private fun TvPlayerOverlay.hidden() =
    copy(controlsVisible = false, focus = TvPlayerControl.PlayPause, skipHold = null)

internal fun TvPlayerOverlay.exit() =
    TvPlayerTransition(copy(exited = true, sheet = null), listOf(TvPlayerCommand.Exit))
