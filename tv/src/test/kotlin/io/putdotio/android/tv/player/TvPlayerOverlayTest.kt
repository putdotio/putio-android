package io.putdotio.android.tv.player

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackTarget
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
    fun backDismissesTheControlsAfterASkipThenExitsOnce() {
        val skipped = TvPlayerOverlay(controlsVisible = false).skipForward(atMillis = 0L).overlay
        assertTrue(skipped.controlsVisible)

        val controlsDismissed = skipped.back()
        assertFalse("A skip leaves nothing to cancel", controlsDismissed.overlay.controlsVisible)
        assertEquals(emptyList<TvPlayerCommand>(), controlsDismissed.commands)

        val exited = controlsDismissed.overlay.back()
        assertEquals(listOf(Exit), exited.commands)

        assertEquals(emptyList<TvPlayerCommand>(), exited.overlay.back().commands)
        assertEquals(emptyList<TvPlayerCommand>(), exited.overlay.ended().commands)
    }

    @Test
    fun anEndedVideoMovesOnOnceWithAutoplayAndLeavesWithout() {
        assertEquals(listOf(Exit), TvPlayerOverlay().ended().commands)

        val advanced = TvPlayerOverlay().skipForward(atMillis = 0L).overlay.ended(autoplayNext = true)
        assertEquals(listOf(PlayNext), advanced.commands)

        assertEquals(emptyList<TvPlayerCommand>(), advanced.overlay.ended(autoplayNext = true).commands)
        assertEquals(emptyList<TvPlayerCommand>(), advanced.overlay.back().commands)
    }

    @Test
    fun anEndBeforeTheUpNextLookupWaitsForIt() {
        val waiting = TvPlayerOverlay().ended(autoplayNext = true, lookup = TvUpNextLookup.Pending)
        assertEquals(emptyList<TvPlayerCommand>(), waiting.commands)
        assertTrue(waiting.overlay.awaitingUpNext)
        assertFalse(waiting.overlay.autoHides)

        val counting = waiting.overlay.upNextResolved(TvUpNextLookup.Found(TvUpNext(HARBOR_NEXT, "Harbor")))
        assertEquals(emptyList<TvPlayerCommand>(), counting.commands)
        assertTrue(counting.overlay.upNext)
        assertFalse(counting.overlay.awaitingUpNext)

        assertEquals(listOf(PlayNext), waiting.overlay.upNextResolved(TvUpNextLookup.FolderEnd).commands)
        assertEquals(listOf(PlayNext), waiting.overlay.upNextResolved(TvUpNextLookup.Failed).commands)
        assertEquals(listOf(PlayNext), TvPlayerOverlay().ended(true, TvUpNextLookup.Failed).commands)

        val cancelled = waiting.overlay.back().overlay
        assertEquals(emptyList<TvPlayerCommand>(), cancelled.upNextResolved(TvUpNextLookup.FolderEnd).commands)
    }

    @Test
    fun aSeekElsewhereCancelsUpNext() {
        val found = TvUpNextLookup.Found(TvUpNext(HARBOR_NEXT, null))
        val counting = TvPlayerOverlay().ended(autoplayNext = true, lookup = found).overlay
        assertTrue(counting.upNext)
        val sought = counting.soughtElsewhere()
        assertFalse(sought.overlay.upNext)
        assertEquals(emptyList<TvPlayerCommand>(), sought.commands)

        val waiting = TvPlayerOverlay().ended(autoplayNext = true, lookup = TvUpNextLookup.Pending).overlay
        assertFalse(waiting.soughtElsewhere().overlay.upNextResolved(found).overlay.upNext)
    }

    @Test
    fun theSystemControlsPausingShowsTheControls() {
        val hidden = TvPlayerOverlay().hideTimedOut().overlay
        val paused = hidden.playingChanged(playing = false)
        assertTrue(paused.overlay.controlsVisible)
        assertEquals("The session already paused the player", emptyList<TvPlayerCommand>(), paused.commands)
        assertEquals(hidden, hidden.playingChanged(playing = true).overlay)
    }

    @Test
    fun aTapSkipsTenSecondsAtOnceAndKeepsThePlayState() {
        val tapped = TvPlayerOverlay(controlsVisible = false).skipForward(atMillis = 0L)
        assertEquals(listOf(TvPlayerCommand.SeekTo(POSITION + 10_000L)), tapped.commands)
        assertTrue("Any press shows the overlay", tapped.overlay.controlsVisible)
        assertEquals("The next tap skips again", TvPlayerControl.SeekBar, tapped.overlay.focus)

        val back = TvPlayerOverlay().skip(
            TvSkipPress(TvSkipDirection.Backward, downAtMillis = 0L, atMillis = 0L, repeat = false),
            positionMillis = POSITION, durationMillis = DURATION,
        )
        assertEquals(listOf(TvPlayerCommand.SeekTo(POSITION - 10_000L)), back.commands)
        assertTrue("A tap never holds the controls", tapped.overlay.autoHides)
    }

    @Test
    fun holdingSeeksAtTwoThenFourThenEightTimesSpeed() {
        val tapped = TvPlayerOverlay().skipForward(atMillis = 0L)
        assertEquals(listOf(TvPlayerCommand.SeekTo(POSITION + 10_000L)), tapped.commands)
        assertFalse(checkNotNull(tapped.overlay.skipHold).accelerating)
        val tooSoon = tapped.overlay.skipForward(atMillis = 50L, repeat = true)
        assertEquals("A 50 ms key repeat is not a step", tapped.overlay, tooSoon.overlay)
        assertEquals(emptyList<TvPlayerCommand>(), tooSoon.commands)

        var overlay = tapped.overlay
        val speeds = (0..2).map { stage ->
            val from = stage * TV_SKIP_HOLD_STAGE_MILLIS + TV_SKIP_HOLD_STEP_MILLIS
            val until = from + 1_000L
            val start = overlay.skipForward(atMillis = from, repeat = true)
            val end = start.overlay.skipForward(atMillis = until, repeat = true)
            overlay = end.overlay
            assertTrue(checkNotNull(overlay.skipHold).accelerating)
            (end.seekTarget() - start.seekTarget()) / (until - from).toDouble()
        }
        assertEquals("Held position moves at 2x, then 4x, then 8x", listOf(2.0, 4.0, 8.0), speeds)

        val released = checkNotNull(overlay.skipReleased()).overlay
        assertNull(released.skipHold)
        assertNull("Nothing to release twice", released.skipReleased())
        val retapped = released.skipForward(atMillis = 20_000L, downAtMillis = 20_000L)
        assertEquals(
            "A new press starts at 10 s",
            listOf(TvPlayerCommand.SeekTo(POSITION + 10_000L)),
            retapped.commands,
        )
    }

    @Test
    fun aForwardHoldStopsShortOfTheEndSoPlaybackDoesNotFinishWhileHeld() {
        val tapped = TvPlayerOverlay().skipForward(atMillis = 0L, positionMillis = DURATION - 30_000L)
        val nearEnd = tapped.overlay.skipForward(atMillis = 60_000L, repeat = true)
        val target = nearEnd.seekTarget()
        assertTrue("Held to $target ms of $DURATION", target in DURATION - 30_000L until DURATION)
        val later = nearEnd.overlay.skipForward(atMillis = 61_000L, repeat = true, positionMillis = target + 900L)
        assertEquals("A hold at the end keeps playback from finishing", target, later.seekTarget())
        assertTrue(later.overlay.activity > nearEnd.overlay.activity)

        val tappedToEnd = TvPlayerOverlay().skipForward(atMillis = 0L, positionMillis = DURATION - 5_000L)
        assertEquals(listOf(TvPlayerCommand.SeekTo(DURATION)), tappedToEnd.commands)
        val heldAtEnd = tappedToEnd.overlay.skipForward(atMillis = 1_000L, repeat = true, positionMillis = DURATION)
        assertEquals("A hold never ends playback again", emptyList<TvPlayerCommand>(), heldAtEnd.commands)

        val rewoundFromEnd = TvPlayerOverlay()
            .skip(TvSkipPress(TvSkipDirection.Backward, 0L, 0L, repeat = true), DURATION, DURATION).overlay
            .skip(TvSkipPress(TvSkipDirection.Backward, 0L, 1_000L, repeat = true), DURATION, DURATION)
        assertEquals(
            "A rewind hold leaves the end",
            listOf(TvPlayerCommand.SeekTo(DURATION - 2_000L)),
            rewoundFromEnd.commands,
        )
    }

    @Test
    fun holdingBackwardRewindsAtTheSameSpeedsAndStopsAtTheStart() {
        val tapped = TvPlayerOverlay().skip(
            TvSkipPress(TvSkipDirection.Backward, downAtMillis = 0L, atMillis = 0L, repeat = false),
            positionMillis = POSITION, durationMillis = DURATION,
        )
        assertEquals(listOf(TvPlayerCommand.SeekTo(POSITION - 10_000L)), tapped.commands)
        val held = tapped.overlay.skip(
            TvSkipPress(TvSkipDirection.Backward, downAtMillis = 0L, atMillis = 1_000L, repeat = true),
            positionMillis = POSITION - 10_000L, durationMillis = DURATION,
        )
        assertEquals(listOf(TvPlayerCommand.SeekTo(POSITION - 10_000L - 2_000L)), held.commands)
        val atStart = held.overlay.skip(
            TvSkipPress(TvSkipDirection.Backward, downAtMillis = 0L, atMillis = 60_000L, repeat = true),
            positionMillis = 0L, durationMillis = DURATION,
        )
        assertEquals(listOf(TvPlayerCommand.SeekTo(0L)), atStart.commands)
        val pastStart = atStart.overlay.skip(
            TvSkipPress(TvSkipDirection.Backward, downAtMillis = 0L, atMillis = 61_000L, repeat = true),
            positionMillis = 900L, durationMillis = DURATION,
        )
        assertEquals("A hold at the start keeps playback there", listOf(TvPlayerCommand.SeekTo(0L)), pastStart.commands)
        assertTrue("The controls stay up while held", pastStart.overlay.activity > atStart.overlay.activity)
    }

    @Test
    fun skippingStaysWithinTheMedia() {
        val start = TvPlayerOverlay().skip(
            TvSkipPress(TvSkipDirection.Backward, downAtMillis = 0L, atMillis = 0L, repeat = false),
            positionMillis = 5_000L, durationMillis = DURATION,
        )
        assertEquals(listOf(TvPlayerCommand.SeekTo(0L)), start.commands)
        val end = TvPlayerOverlay().skipForward(atMillis = 0L, positionMillis = DURATION - 1_000L)
        assertEquals(listOf(TvPlayerCommand.SeekTo(DURATION)), end.commands)
        val unknown = TvPlayerOverlay().skip(
            TvSkipPress(TvSkipDirection.Forward, downAtMillis = 0L, atMillis = 0L, repeat = false),
            positionMillis = 0L, durationMillis = 0L,
        )
        assertNull(unknown.overlay.skipHold)
        assertEquals(emptyList<TvPlayerCommand>(), unknown.commands)
    }

    @Test
    fun thePlayAndPauseKeysMapDirectly() {
        val skipped = TvPlayerOverlay().skipForward(atMillis = 0L).overlay
        assertEquals(listOf(Play), skipped.setPlaying(play = true).commands)
        assertEquals(listOf(Pause), skipped.setPlaying(play = false).commands)
        assertEquals(listOf(Pause), skipped.select(playing = true).commands)
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
        val returned = timing.back().overlay
        assertEquals("Back returns to Subtitles", TvPlayerSheet.Subtitles, returned.sheet)
        assertTrue("on the row that opened Size and timing", returned.backFromSizeAndTiming)
        assertFalse(returned.closeSheet().overlay.openSheet(TvPlayerControl.Subtitles).overlay.backFromSizeAndTiming)

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
        val skipped = TvPlayerOverlay().skipForward(atMillis = 0L).overlay
        assertEquals(TvPlayerControl.SeekBar, skipped.focus)
        assertEquals(TvPlayerControl.Audio, skipped.moveFocus(TvFocusMove.Down, chips).overlay.focus)
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

        val chosen = TvPlayerOverlay().chooseNext()
        assertEquals(listOf(TvPlayerCommand.ChooseNext), chosen.commands)
        assertFalse("The controller leaves only once the next video resolves", chosen.overlay.exited)
    }

    private fun TvPlayerOverlay.skipForward(
        atMillis: Long,
        repeat: Boolean = false,
        downAtMillis: Long = 0L,
        positionMillis: Long = POSITION,
    ) = skip(TvSkipPress(TvSkipDirection.Forward, downAtMillis, atMillis, repeat), positionMillis, DURATION)

    private fun TvPlayerTransition.seekTarget(): Long {
        val seek = commands.single()
        check(seek is TvPlayerCommand.SeekTo)
        return seek.positionMillis
    }

    private companion object {
        const val POSITION = 60_000L
        const val DURATION = 3_600_000L
    }
}

private val HARBOR_NEXT = PlaybackTarget(FilesItemId(10), "Harbor film 2.mp4")
