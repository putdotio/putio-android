package io.putdotio.android.tv.player

import io.putdotio.android.tv.player.TvPlayerCommand.Exit
import io.putdotio.android.tv.player.TvPlayerCommand.Pause
import io.putdotio.android.tv.player.TvPlayerCommand.Play
import io.putdotio.android.tv.player.TvPlayerCommand.PlayNext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPlayerOverlayTest {
    @Test
    fun backDismissesSeekModeThenTheControlsThenExitsOnce() {
        val scrubbing = TvPlayerOverlay().scrubForward(nowMillis = 0L).overlay

        val seekDismissed = scrubbing.back()
        assertNull(seekDismissed.overlay.scrub)
        assertTrue("Only seek mode is dismissed", seekDismissed.overlay.controlsVisible)
        assertEquals("The scrub paused playback, so Back resumes it", listOf(Play), seekDismissed.commands)

        val controlsDismissed = seekDismissed.overlay.back()
        assertFalse(controlsDismissed.overlay.controlsVisible)
        assertEquals(emptyList<TvPlayerCommand>(), controlsDismissed.commands)

        val exited = controlsDismissed.overlay.back()
        assertEquals(listOf(Exit), exited.commands)

        assertEquals(emptyList<TvPlayerCommand>(), exited.overlay.back().commands)
        assertEquals(emptyList<TvPlayerCommand>(), exited.overlay.ended().commands)
    }

    @Test
    fun anEndedVideoMovesOnOnceWithAutoplayAndLeavesWithout() {
        assertEquals(listOf(Exit), TvPlayerOverlay().ended().commands)

        val advanced = TvPlayerOverlay().scrubForward(nowMillis = 0L).overlay.ended(autoplayNext = true)
        assertEquals(listOf(PlayNext), advanced.commands)
        assertNull(advanced.overlay.scrub)

        assertEquals(emptyList<TvPlayerCommand>(), advanced.overlay.ended(autoplayNext = true).commands)
        assertEquals(emptyList<TvPlayerCommand>(), advanced.overlay.back().commands)
    }

    @Test
    fun theSystemControlsPausingShowsTheControlsAndPlayingDropsAPendingScrub() {
        val hidden = TvPlayerOverlay().hideTimedOut().overlay
        val paused = hidden.playingChanged(playing = false)
        assertTrue(paused.overlay.controlsVisible)
        assertEquals("The session already paused the player", emptyList<TvPlayerCommand>(), paused.commands)

        val scrubbing = TvPlayerOverlay().scrubForward(nowMillis = 0L, playing = false).overlay
        val played = scrubbing.playingChanged(playing = true)
        assertNull(played.overlay.scrub)
        assertEquals(emptyList<TvPlayerCommand>(), played.commands)

        assertEquals(scrubbing.scrub, scrubbing.playingChanged(playing = false).overlay.scrub)
    }

    @Test
    fun dismissingSeekModeStartedWhilePausedStaysPausedAndNeverSeeks() {
        val scrubbing = TvPlayerOverlay().scrubForward(nowMillis = 0L, playing = false)
        assertEquals("Already paused", emptyList<TvPlayerCommand>(), scrubbing.commands)

        val dismissed = scrubbing.overlay.back()
        assertNull(dismissed.overlay.scrub)
        assertEquals(emptyList<TvPlayerCommand>(), dismissed.commands)
    }

    @Test
    fun aHeldKeyGrowsTheStepAtTheHoldCadenceOnly() {
        val first = TvPlayerOverlay().scrubForward(nowMillis = 0L)
        val tooSoon = first.overlay.scrubForward(nowMillis = 50L, repeat = true)
        assertEquals("A 50 ms key repeat is not a press", first.overlay, tooSoon.overlay)

        val held = first.overlay.scrubForward(nowMillis = TV_SCRUB_HOLD_REPEAT_MILLIS, repeat = true)
        assertEquals(2, held.overlay.scrub?.presses)
        val stillHeld = held.overlay.scrubForward(nowMillis = TV_SCRUB_HOLD_REPEAT_MILLIS + 600L, repeat = true)
        assertEquals(3, stillHeld.overlay.scrub?.presses)
        assertEquals(POSITION + 15_000L + 30_000L + 45_000L, stillHeld.overlay.scrub?.targetMillis)
    }

    @Test
    fun scrubbingStaysWithinTheMedia() {
        val start = TvPlayerOverlay().scrub(
            TvScrubPress(TvScrubDirection.Backward, atMillis = 0L, repeat = false),
            positionMillis = 5_000L, durationMillis = DURATION, playing = true,
        )
        assertEquals(0L, start.overlay.scrub?.targetMillis)
        val end = TvPlayerOverlay().scrub(
            TvScrubPress(TvScrubDirection.Forward, atMillis = 0L, repeat = false),
            positionMillis = DURATION - 1_000L, durationMillis = DURATION, playing = true,
        )
        assertEquals(DURATION, end.overlay.scrub?.targetMillis)
        val unknown = TvPlayerOverlay().scrub(
            TvScrubPress(TvScrubDirection.Forward, atMillis = 0L, repeat = false),
            positionMillis = 0L, durationMillis = 0L, playing = true,
        )
        assertNull(unknown.overlay.scrub)
        assertEquals(emptyList<TvPlayerCommand>(), unknown.commands)
    }

    @Test
    fun thePlayAndPauseKeysDropAPendingScrub() {
        val scrubbing = TvPlayerOverlay().scrubForward(nowMillis = 0L).overlay
        val played = scrubbing.setPlaying(play = true)
        assertNull(played.overlay.scrub)
        assertEquals(listOf(Play), played.commands)
        assertEquals(listOf(Pause), scrubbing.setPlaying(play = false).commands)
    }

    @Test
    fun anOpenSheetIsTheTopmostLayerAndClosingItKeepsFocusOnItsChip() {
        val chips = listOf(TvPlayerControl.Subtitles, TvPlayerControl.Speed)
        val subtitles = TvPlayerOverlay().moveFocus(TvFocusMove.Down, chips).overlay
            .moveFocus(TvFocusMove.Down, chips).overlay
        val open = subtitles.openSheet(TvPlayerControl.Subtitles).overlay
        assertEquals(TvPlayerSheet.Subtitles, open.sheet)
        assertTrue("An open sheet holds the controls", open.hideTimedOut().overlay.controlsVisible)

        val timing = open.openSizeAndTiming().overlay
        assertEquals(TvPlayerSheet.SizeAndTiming, timing.sheet)
        assertEquals("Back returns to Subtitles", TvPlayerSheet.Subtitles, timing.back().overlay.sheet)

        val closed = open.back()
        assertNull(closed.overlay.sheet)
        assertTrue("Only the sheet is dismissed", closed.overlay.controlsVisible)
        assertEquals(TvPlayerControl.Subtitles, closed.overlay.focus)
        assertEquals(emptyList<TvPlayerCommand>(), closed.commands)

        val hidden = closed.overlay.back()
        assertFalse(hidden.overlay.controlsVisible)
        assertEquals("Hidden controls come back on play and pause", TvPlayerControl.PlayPause, hidden.overlay.focus)
        assertEquals(listOf(Exit), hidden.overlay.back().commands)
    }

    @Test
    fun theDpadStepsBetweenTheCirclesTheSeekBarAndTheChips() {
        val chips = listOf(TvPlayerControl.Audio, TvPlayerControl.Subtitles, TvPlayerControl.Speed)
        val start = TvPlayerOverlay()
        assertEquals(TvPlayerControl.PlayPause, start.focus)
        assertEquals(TvPlayerControl.Back, start.moveFocus(TvFocusMove.Left, chips).overlay.focus)
        assertEquals(TvPlayerControl.Forward, start.moveFocus(TvFocusMove.Right, chips).overlay.focus)
        val up = start.moveFocus(TvFocusMove.Up, chips).overlay
        assertEquals("Up stops at the circles", TvPlayerControl.PlayPause, up.focus)

        val seekBar = start.moveFocus(TvFocusMove.Left, chips).overlay.moveFocus(TvFocusMove.Down, chips).overlay
        assertEquals(TvPlayerControl.SeekBar, seekBar.focus)
        assertEquals(TvPlayerControl.PlayPause, seekBar.moveFocus(TvFocusMove.Up, chips).overlay.focus)

        val audio = seekBar.moveFocus(TvFocusMove.Down, chips).overlay
        assertEquals(TvPlayerControl.Audio, audio.focus)
        val left = audio.moveFocus(TvFocusMove.Left, chips).overlay
        assertEquals("Left stops at the first chip", TvPlayerControl.Audio, left.focus)
        val speed = audio.moveFocus(TvFocusMove.Right, chips).overlay.moveFocus(TvFocusMove.Right, chips).overlay
        assertEquals(TvPlayerControl.Speed, speed.focus)
        assertEquals(TvPlayerControl.Speed, speed.moveFocus(TvFocusMove.Right, chips).overlay.focus)
        assertEquals(TvPlayerControl.Speed, speed.moveFocus(TvFocusMove.Down, chips).overlay.focus)
        assertEquals(TvPlayerControl.SeekBar, speed.moveFocus(TvFocusMove.Up, chips).overlay.focus)

        assertEquals(TvPlayerControl.SeekBar, audio.focusIn(listOf(TvPlayerControl.Speed)))
        val hidden = TvPlayerOverlay(controlsVisible = false).moveFocus(TvFocusMove.Down, chips).overlay
        assertTrue(hidden.controlsVisible)
        assertEquals(TvPlayerControl.PlayPause, hidden.focus)
        val scrubbing = TvPlayerOverlay().scrubForward(nowMillis = 0L).overlay
        assertEquals(TvPlayerControl.SeekBar, scrubbing.moveFocus(TvFocusMove.Down, chips).overlay.focus)
    }

    @Test
    fun theCirclesSkipTenSecondsWithinTheMediaAndNextEpisodeMovesOnOnce() {
        assertEquals(
            listOf(TvPlayerCommand.SeekTo(POSITION + 10_000L)),
            TvPlayerOverlay().skip(true, POSITION, DURATION).commands,
        )
        assertEquals(listOf(TvPlayerCommand.SeekTo(0L)), TvPlayerOverlay().skip(false, 4_000L, DURATION).commands)
        assertEquals(
            listOf(TvPlayerCommand.SeekTo(DURATION)),
            TvPlayerOverlay().skip(true, DURATION - 1_000L, DURATION).commands,
        )

        val next = TvPlayerOverlay().playNext()
        assertEquals(listOf(PlayNext), next.commands)
        assertEquals(emptyList<TvPlayerCommand>(), next.overlay.playNext().commands)
    }

    private fun TvPlayerOverlay.scrubForward(nowMillis: Long, playing: Boolean = true, repeat: Boolean = false) =
        scrub(TvScrubPress(TvScrubDirection.Forward, nowMillis, repeat), POSITION, DURATION, playing)

    private companion object {
        const val POSITION = 60_000L
        const val DURATION = 3_600_000L
    }
}
