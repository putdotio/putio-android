package io.putdotio.android.tv.player

internal enum class TvPlayerControl {
    Back,
    PlayPause,
    Forward,
    Previous,
    Next,
    SeekBar,
    Audio,
    Subtitles,
    Speed,
    NextEpisode,
}

internal enum class TvFocusMove { Up, Down, Left, Right }

private val TV_PLAYER_CIRCLES = listOf(TvPlayerControl.Back, TvPlayerControl.PlayPause, TvPlayerControl.Forward)
private val TV_NOW_PLAYING_TRANSPORT = listOf(TvPlayerControl.Previous, TvPlayerControl.PlayPause, TvPlayerControl.Next)

private val TvPlayerOverlay.circles: List<TvPlayerControl>
    get() = if (nowPlaying) TV_NOW_PLAYING_TRANSPORT else TV_PLAYER_CIRCLES

internal fun TvPlayerOverlay.moveFocus(move: TvFocusMove, chips: List<TvPlayerControl>): TvPlayerTransition {
    val revealed = reveal().overlay
    if (!controlsVisible || exited || scrub != null) return TvPlayerTransition(revealed)
    val current = focusIn(chips)
    val seekBar = listOf(TvPlayerControl.SeekBar)
    val rows = if (nowPlaying) listOf(seekBar, circles, chips) else listOf(circles, seekBar, chips)
    val rowIndex = rows.indexOfFirst { current in it }
    val row = rows[rowIndex]
    val index = row.indexOf(current)
    val next = when (move) {
        TvFocusMove.Up -> rows.getOrNull(rowIndex - 1)?.entry()
        TvFocusMove.Down -> rows.getOrNull(rowIndex + 1)?.entry()
        TvFocusMove.Left -> row.getOrNull(index - 1)
        TvFocusMove.Right -> row.getOrNull(index + 1)
    } ?: current
    return TvPlayerTransition(revealed.copy(focus = next))
}

private fun List<TvPlayerControl>.entry(): TvPlayerControl? =
    if (TvPlayerControl.PlayPause in this) TvPlayerControl.PlayPause else firstOrNull()

internal fun TvPlayerOverlay.focusIn(chips: List<TvPlayerControl>): TvPlayerControl =
    focus.takeIf { it in circles || it == TvPlayerControl.SeekBar || it in chips }
        ?: TvPlayerControl.SeekBar

internal fun TvPlayerOverlay.openSheet(chip: TvPlayerControl): TvPlayerTransition {
    val sheet = when (chip) {
        TvPlayerControl.Audio -> TvPlayerSheet.Audio
        TvPlayerControl.Subtitles -> TvPlayerSheet.Subtitles
        TvPlayerControl.Speed -> TvPlayerSheet.Speed
        else -> null
    }
    return if (exited || sheet == null) {
        TvPlayerTransition(this)
    } else {
        TvPlayerTransition(
            copy(
                controlsVisible = true,
                sheet = sheet,
                backFromSizeAndTiming = false,
                focus = chip,
                activity = activity + 1,
            ),
        )
    }
}

internal fun TvPlayerOverlay.openSizeAndTiming(): TvPlayerTransition =
    if (sheet == TvPlayerSheet.Subtitles) {
        TvPlayerTransition(
            copy(sheet = TvPlayerSheet.SizeAndTiming, backFromSizeAndTiming = false, activity = activity + 1),
        )
    } else {
        TvPlayerTransition(this)
    }

internal fun TvPlayerOverlay.closeSheet(): TvPlayerTransition =
    TvPlayerTransition(copy(sheet = null, backFromSizeAndTiming = false, activity = activity + 1))

internal fun TvPlayerOverlay.chooseNext(): TvPlayerTransition =
    if (exited) TvPlayerTransition(this) else TvPlayerTransition(this, listOf(TvPlayerCommand.ChooseNext))

internal fun TvPlayerOverlay.playNext(): TvPlayerTransition =
    if (exited) {
        TvPlayerTransition(this)
    } else {
        TvPlayerTransition(copy(exited = true, scrub = null, sheet = null), listOf(TvPlayerCommand.PlayNext))
    }
