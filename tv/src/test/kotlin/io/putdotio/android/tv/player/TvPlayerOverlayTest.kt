package io.putdotio.android.tv.player

import io.putdotio.android.tv.player.TvPlayerCommand.Exit
import io.putdotio.android.tv.player.TvPlayerCommand.Pause
import io.putdotio.android.tv.player.TvPlayerCommand.Play
import io.putdotio.android.tv.player.TvPlayerCommand.PlayNext
import io.putdotio.android.tv.player.TvPlayerCommand.SeekTo
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

        // A repeated Back, or playback ending, before the player leaves does nothing more.
        assertEquals(emptyList<TvPlayerCommand>(), exited.overlay.back().commands)
        assertEquals(emptyList<TvPlayerCommand>(), exited.overlay.ended().commands)
    }

    @Test
    fun anEndedVideoMovesOnOnceWithAutoplayAndLeavesWithout() {
        assertEquals(listOf(Exit), TvPlayerOverlay().ended().commands)

        val advanced = TvPlayerOverlay().scrubForward(nowMillis = 0L).overlay.ended(autoplayNext = true)
        assertEquals(listOf(PlayNext), advanced.commands)
        assertNull(advanced.overlay.scrub)

        // Repeated end signals, and Backs, before the next video takes over do nothing more.
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

        // A scrub pausing the player keeps its target.
        assertEquals(scrubbing.scrub, scrubbing.playingChanged(playing = false).overlay.scrub)
    }

    @Test
    fun dismissingPausedControlsKeepsPlaybackPaused() {
        val paused = TvPlayerOverlay().select(playing = true)
        assertEquals(listOf(Pause), paused.commands)

        val hidden = paused.overlay.back()
        assertFalse(hidden.overlay.controlsVisible)
        assertEquals("No resume, no exit", emptyList<TvPlayerCommand>(), hidden.commands)
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
    fun quickPressesGrowTheStepAndAGapRestartsItFromThePendingTarget() {
        val first = TvPlayerOverlay().scrubForward(nowMillis = 1_000L)
        assertEquals("The first press pauses", listOf(Pause), first.commands)
        assertEquals(POSITION + 15_000L, first.overlay.scrub?.targetMillis)

        val second = first.overlay.scrubForward(nowMillis = 1_000L + TV_SCRUB_ACCUMULATE_MILLIS - 1)
        assertEquals(emptyList<TvPlayerCommand>(), second.commands)
        assertEquals(POSITION + 15_000L + 30_000L, second.overlay.scrub?.targetMillis)

        val afterGap = second.overlay.scrubForward(nowMillis = 1_000L + 2 * TV_SCRUB_ACCUMULATE_MILLIS)
        assertEquals(1, afterGap.overlay.scrub?.presses)
        assertEquals(POSITION + 15_000L + 30_000L + 15_000L, afterGap.overlay.scrub?.targetMillis)
    }

    @Test
    fun aHeldKeyGrowsTheStepAtTheHoldCadenceOnly() {
        val first = TvPlayerOverlay().scrubForward(nowMillis = 0L)
        val tooSoon = first.overlay.scrubForward(nowMillis = 50L, repeat = true)
        assertEquals("A 50 ms key repeat is not a press", first.overlay, tooSoon.overlay)

        val held = first.overlay.scrubForward(nowMillis = TV_SCRUB_HOLD_REPEAT_MILLIS, repeat = true)
        assertEquals(2, held.overlay.scrub?.presses)
        // Repeats keep counting even past the tap window.
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
    fun selectCommitsAScrubAndPlaysFromThere() {
        val scrubbing = TvPlayerOverlay().scrubForward(nowMillis = 0L, playing = false).overlay
        val committed = scrubbing.select(playing = false)
        assertNull(committed.overlay.scrub)
        assertEquals(listOf(SeekTo(POSITION + 15_000L), Play), committed.commands)

        assertEquals(listOf(Pause), committed.overlay.select(playing = true).commands)
        assertEquals(listOf(Play), committed.overlay.select(playing = false).commands)
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
    fun theControlsHideOnTimeoutOutsideSeekModeOnly() {
        val shown = TvPlayerOverlay(controlsVisible = false).reveal().overlay
        assertTrue(shown.controlsVisible)
        assertFalse(shown.hideTimedOut().overlay.controlsVisible)

        val scrubbing = shown.scrubForward(nowMillis = 0L).overlay
        assertTrue(scrubbing.hideTimedOut().overlay.controlsVisible)
    }

    @Test
    fun anOpenPickerIsTheTopmostLayerAndClosingItKeepsFocusOnItsButton() {
        val speed = TvPlayerOverlay().moveFocus(TvFocusMove.Up, listOf(TvPlayerControl.Speed)).overlay
        val picking = speed.openPicker(TvPlayerControl.Speed).overlay
        assertEquals(TvPlayerControl.Speed, picking.picker)
        assertTrue("An open picker holds the controls", picking.hideTimedOut().overlay.controlsVisible)

        val closed = picking.back()
        assertNull(closed.overlay.picker)
        assertTrue("Only the picker is dismissed", closed.overlay.controlsVisible)
        assertEquals(TvPlayerControl.Speed, closed.overlay.focus)
        assertEquals(emptyList<TvPlayerCommand>(), closed.commands)

        val hidden = closed.overlay.back()
        assertFalse(hidden.overlay.controlsVisible)
        assertEquals("Hidden controls come back on the seek bar", TvPlayerControl.SeekBar, hidden.overlay.focus)
        assertEquals(listOf(Exit), hidden.overlay.back().commands)
    }

    @Test
    fun theDpadWalksTheButtonsAndReturnsToTheSeekBar() {
        val buttons = listOf(TvPlayerControl.Language, TvPlayerControl.Subtitles, TvPlayerControl.Speed)
        val up = TvPlayerOverlay().moveFocus(TvFocusMove.Up, buttons).overlay
        assertEquals(TvPlayerControl.Language, up.focus)
        assertEquals(
            "Left stops at the first button",
            up,
            up.moveFocus(TvFocusMove.Left, buttons).overlay.copy(activity = up.activity),
        )
        val right = up.moveFocus(TvFocusMove.Right, buttons).overlay.moveFocus(TvFocusMove.Right, buttons).overlay
        assertEquals(TvPlayerControl.Speed, right.focus)
        assertEquals(TvPlayerControl.Speed, right.moveFocus(TvFocusMove.Right, buttons).overlay.focus)
        assertEquals(TvPlayerControl.Speed, right.moveFocus(TvFocusMove.Up, buttons).overlay.focus)
        assertEquals(TvPlayerControl.SeekBar, right.moveFocus(TvFocusMove.Down, buttons).overlay.focus)

        // A button whose tracks went away counts as the seek bar.
        assertEquals(TvPlayerControl.SeekBar, up.focusIn(listOf(TvPlayerControl.Speed)))
        // Hidden controls only come back; seek mode keeps the seek bar.
        val hidden = TvPlayerOverlay(controlsVisible = false).moveFocus(TvFocusMove.Up, buttons).overlay
        assertTrue(hidden.controlsVisible)
        assertEquals(TvPlayerControl.SeekBar, hidden.focus)
        val scrubbing = TvPlayerOverlay().scrubForward(nowMillis = 0L).overlay
        assertEquals(TvPlayerControl.SeekBar, scrubbing.moveFocus(TvFocusMove.Up, buttons).overlay.focus)
    }

    @Test
    fun rewindAndFastForwardPullFocusBackToTheSeekBar() {
        val onButton = TvPlayerOverlay().moveFocus(TvFocusMove.Up, listOf(TvPlayerControl.Speed)).overlay
        assertEquals(TvPlayerControl.Speed, onButton.focus)
        assertEquals(TvPlayerControl.SeekBar, onButton.scrubForward(nowMillis = 0L).overlay.focus)
    }

    private fun TvPlayerOverlay.scrubForward(nowMillis: Long, playing: Boolean = true, repeat: Boolean = false) =
        scrub(TvScrubPress(TvScrubDirection.Forward, nowMillis, repeat), POSITION, DURATION, playing)

    private companion object {
        const val POSITION = 60_000L
        const val DURATION = 3_600_000L
    }
}
