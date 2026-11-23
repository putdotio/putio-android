package io.putdotio.android.tv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.view.KeyEvent as AndroidKeyEvent
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.tv.setTvContent
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import java.io.Closeable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.playback.PlaybackContent
import java.io.IOException
import io.putdotio.android.PutioFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.putdotio.android.playback.copyForTest

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlayerScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aFailureBehindTheShellBringsThePlayerBackToShowIt() {
        var shown = 0
        val failed = readyState(name = "song.mp3", mediaType = PlaybackMediaType.AUDIO).copyForTest(
            content = PlaybackContent.Failed(
                PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IOException("offline"))),
            ),
        )
        compose.setTvContent {
            TvPlayerScreen(
                state = failed,
                onBack = {},
                onRetry = {},
                onPlayerFailure = { _, _ -> },
                playerFactory = { _, _ -> FakePlayer() },
                background = true,
                onBackground = {},
                onForeground = { shown += 1 },
            )
        }
        compose.runOnIdle { assertEquals(1, shown) }
    }

    @Test
    fun readySourcePlaysAndCenterTogglesPauseWithTheControlsShown() {
        val player = FakePlayer()
        compose.mainClock.autoAdvance = false
        compose.setTvContent {
            TvPlayerScreen(
                state = readyState(resumePositionMillis = 42_000L),
                onBack = {},
                onRetry = {},
                onPlayerFailure = { _, _ -> },
                playerFactory = { _, _ -> player },
            )
        }
        compose.settle()
        compose.runOnIdle {
            val item = player.mediaItems.single()
            assertEquals("9", item.mediaId)
            assertEquals(SOURCE_URL, item.localConfiguration?.uri.toString())
            assertEquals(42_000L, player.startPositionMillis)
            assertTrue(player.prepared)
            assertTrue(player.playWhenReady)
        }
        compose.onNodeWithText("Sintel.mp4").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()

        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.settle()
        compose.runOnIdle { assertFalse(player.playWhenReady) }
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS * 2)
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.MediaPlayPause) }
        compose.settle()
        compose.runOnIdle { assertTrue(player.playWhenReady) }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + 100L)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        compose.settle()
        compose.onNodeWithText("Sintel.mp4").assertIsDisplayed()
        compose.runOnIdle { assertTrue(player.playWhenReady) }
    }

    @Test
    fun leavingTheAppPausesAndARecreatedScreenContinuesFromThereStillPaused() {
        val players = mutableListOf<FakePlayer>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PutioTvTheme {
                TvPlayerScreen(
                    state = readyState(resumePositionMillis = 42_000L),
                    onBack = {},
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> FakePlayer().also { players += it } },
                )
            }
        }
        compose.runOnIdle { players.single().advanceTo(61_000L) }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnIdle { assertTrue(players.single().released) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertEquals(61_000L, players[1].startPositionMillis)
            assertFalse(players[1].playWhenReady)
        }

        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(3, players.size)
            assertTrue(players[1].released)
            val recreated = players.last()
            assertEquals(61_000L, recreated.startPositionMillis)
            assertFalse(recreated.playWhenReady)
        }
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test
    fun aPlayerErrorReportsItsPositionAndTheFailureOffersRetry() {
        val player = FakePlayer()
        var reported: Pair<PlaybackFailure, Long>? = null
        var retries = 0
        var state by mutableStateOf(readyState())
        compose.setTvContent {
            TvPlayerScreen(
                state = state,
                onBack = {},
                onRetry = { retries += 1 },
                onPlayerFailure = { failure, position -> reported = failure to position },
                playerFactory = { _, _ -> player },
            )
        }
        compose.runOnIdle { player.fail(positionMillis = 61_000L) }
        compose.waitForIdle()
        val (failure, position) = checkNotNull(reported)
        assertEquals(61_000L, position)

        state = state.copyForTest(content = PlaybackContent.Failed(failure))
        compose.onNodeWithText("Playback error").assertIsDisplayed()
        compose.onNodeWithText("Go back").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun leftAndRightOnTheSeekBarSkipTenSecondsAtOnceAndKeepPlaying() {
        val player = FakePlayer()
        compose.showReady(player, resumePositionMillis = 42_000L)

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
        }
        compose.settle()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("1:02")
        compose.runOnIdle {
            assertEquals(62_000L, player.currentPosition)
            assertTrue("A skip never pauses", player.playWhenReady)
        }

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.settle()
        compose.runOnIdle {
            assertEquals(52_000L, player.currentPosition)
            assertTrue(player.playWhenReady)
        }
    }

    @Test
    fun aTapWithTheControlsHiddenSkipsAndRewindAndFastForwardFollowTheSameRule() {
        val player = FakePlayer()
        compose.showReady(player, resumePositionMillis = 42_000L)
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS * 2)
        compose.settle()
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.settle()
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertIsDisplayed()
        compose.runOnIdle { assertEquals(32_000L, player.currentPosition) }

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput {
            pressKey(Key.MediaFastForward)
            pressKey(Key.MediaFastForward)
            pressKey(Key.MediaRewind)
        }
        compose.settle()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("0:42")
        compose.runOnIdle { assertTrue(player.playWhenReady) }
    }

    @Test
    fun backAfterASkipDismissesTheControlsKeepingTimeAndFocusThenExitsOnce() {
        val player = FakePlayer()
        var backs = 0
        compose.showReady(player, resumePositionMillis = 42_000L, onBack = { backs += 1 })

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionLeft)
        }
        compose.settle()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("0:32")

        compose.back()
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        compose.runOnIdle {
            assertEquals(0, backs)
            assertEquals("Back has no seek to undo", 32_000L, player.currentPosition)
            assertTrue(player.playWhenReady)
        }

        compose.back()
        compose.back()
        compose.runOnIdle { assertEquals("Exit dispatches once", 1, backs) }
    }

    @Test
    fun pausedControlsDismissWithoutResumingOrLeaving() {
        val player = FakePlayer()
        var backs = 0
        compose.showReady(player, onBack = { backs += 1 })

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.settle()
        compose.runOnIdle { assertFalse(player.playWhenReady) }

        compose.back()
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        compose.runOnIdle {
            assertFalse("Still paused", player.playWhenReady)
            assertEquals(0, backs)
        }

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionUp) }
        compose.settle()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.runOnIdle { assertFalse(player.playWhenReady) }
    }

    @Test
    fun aHeldBackKeyDismissesOneLayer() {
        val player = FakePlayer()
        var backs = 0
        compose.showReady(player, onBack = { backs += 1 })
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertIsDisplayed()

        compose.runOnUiThread {
            val down = AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_BACK, 0)
            compose.activity.dispatchKeyEvent(down)
            repeat(HELD_REPEATS) { count ->
                val flags = if (count == 0) AndroidKeyEvent.FLAG_LONG_PRESS else 0
                val held = AndroidKeyEvent.changeTimeRepeat(down, 50L * (count + 1), count + 1, flags)
                compose.activity.dispatchKeyEvent(held)
            }
            compose.activity.dispatchKeyEvent(AndroidKeyEvent.changeAction(down, AndroidKeyEvent.ACTION_UP))
        }
        compose.settle()

        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        compose.runOnIdle { assertEquals("The held key did not also leave", 0, backs) }
    }

    @Test
    fun leavingForegroundReleasesPlayerMediaSessionAndReporterThenRebuildsOnStart() {
        val delegates = mutableListOf<FakePlayer>()
        val players = mutableListOf<RejectAfterReleasePlayer>()
        var sessionsPublished = 0
        var sessionsClosed = 0
        var observations = 0
        var observationsClosed = 0
        val owner = TestLifecycleOwner()
        val factory = object : TvPlayerFactory {
            override fun create(context: Context, mediaType: PlaybackMediaType): Player =
                RejectAfterReleasePlayer(FakePlayer().also { delegates += it }).also { players += it }

            override fun publish(context: Context, player: Player): Closeable {
                sessionsPublished += 1
                return Closeable { sessionsClosed += 1 }
            }
        }
        val reporter = object : TvPlaybackReporter {
            override fun lease(fileId: Long): String? = null
            override fun observe(player: Player): Closeable {
                observations += 1
                return Closeable { observationsClosed += 1 }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PutioTvTheme {
                    TvPlayerScreen(
                        state = readyState(resumePositionMillis = 42_000L),
                        onBack = {},
                        onRetry = {},
                        onPlayerFailure = { _, _ -> },
                        playerFactory = factory,
                        reporter = reporter,
                    )
                }
            }
        }
        compose.settle()
        compose.runOnIdle {
            assertEquals(1, players.size)
            assertEquals(1, sessionsPublished)
            assertEquals(1, observations)
            delegates.single().advanceTo(61_000L)
        }
        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.CREATED
            assertTrue(players.first().released)
            assertEquals(1, players.first().releaseCount)
            assertEquals(1, sessionsClosed)
            assertEquals(1, observationsClosed)
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.settle()
        assertFalse("Stopped controls must not poll released player", players.first().accessedAfterRelease)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.settle()
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertFalse(players[1].released)
            assertEquals(61_000L, delegates[1].startPositionMillis)
            assertFalse(players[1].playWhenReady)
            assertEquals(2, sessionsPublished)
            assertEquals(2, observations)
        }
    }

    @Test
    fun stoppedClockCoalescedLifecycleCycleStillReleasesOldPlayerAndRebuilds() {
        val delegates = mutableListOf<TrackPlayer>()
        val players = mutableListOf<RejectAfterReleasePlayer>()
        var sessionsClosed = 0
        val owner = TestLifecycleOwner()
        val factory = object : TvPlayerFactory {
            override fun create(context: Context, mediaType: PlaybackMediaType): Player {
                val trackPlayer = TrackPlayer(audio = null, text = subtitleTrackGroup()).also { delegates += it }
                return RejectAfterReleasePlayer(trackPlayer).also { players += it }
            }

            override fun publish(context: Context, player: Player): Closeable =
                Closeable { sessionsClosed += 1 }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PutioTvTheme {
                    TvPlayerScreen(
                        state = readyState(resumePositionMillis = 42_000L),
                        onBack = {},
                        onRetry = {},
                        onPlayerFailure = { _, _ -> },
                        playerFactory = factory,
                    )
                }
            }
        }
        compose.settle()
        addPositiveSubtitleDelay()
        compose.runOnIdle {
            delegates.single().advanceTo(61_000L)
            delegates.single().emitCue("delayed subtitle")
        }
        compose.settle()

        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.settle()

        compose.runOnIdle {
            assertEquals(2, players.size)
            assertTrue(players[0].released)
            assertEquals(1, players[0].releaseCount)
            assertFalse("Coalesced restart must not read released player", players[0].accessedAfterRelease)
            assertEquals(1, sessionsClosed)
            assertTrue(delegates[1].currentPosition in 61_000L..65_000L)
            assertFalse(players[1].playWhenReady)
        }
    }

    @Test
    fun foregroundAudioCoalescedLifecycleCycleDoesNotAccessReleasedPlayer() {
        val delegates = mutableListOf<FakePlayer>()
        val players = mutableListOf<RejectAfterReleasePlayer>()
        var sessionsClosed = 0
        val owner = TestLifecycleOwner()
        val factory = object : TvPlayerFactory {
            override fun create(context: Context, mediaType: PlaybackMediaType): Player =
                RejectAfterReleasePlayer(FakePlayer().also { delegates += it }).also { players += it }

            override fun publish(context: Context, player: Player): Closeable =
                Closeable { sessionsClosed += 1 }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PutioTvTheme {
                    TvPlayerScreen(
                        state = readyState(
                            name = "song.mp3",
                            mediaType = PlaybackMediaType.AUDIO,
                            resumePositionMillis = 42_000L,
                        ),
                        onBack = {},
                        onRetry = {},
                        onPlayerFailure = { _, _ -> },
                        playerFactory = factory,
                    )
                }
            }
        }
        compose.settle()
        compose.runOnIdle { delegates.single().advanceTo(61_000L) }
        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.settle()

        compose.runOnIdle {
            assertEquals(2, players.size)
            assertTrue(players[0].released)
            assertEquals(1, players[0].releaseCount)
            assertEquals(0, players[0].removedAfterRelease)
            assertFalse("Coalesced audio restart must not access released player", players[0].accessedAfterRelease)
            assertEquals(1, sessionsClosed)
            assertEquals(61_000L, delegates[1].startPositionMillis)
        }
    }

    @Test
    fun stoppedControlsDisposeWithoutRemovingListenerFromReleasedPlayer() {
        val spy = RejectAfterReleasePlayer(FakePlayer())
        var show by mutableStateOf(true)
        var released by mutableStateOf(false)
        compose.setTvContent {
            if (show) {
                TvPlayerControls(
                    player = spy,
                    title = "Sample.mkv",
                    paused = false,
                    seeking = false,
                    startPositionMillis = 0L,
                    listingDurationMillis = 120_000L,
                    chips = listOf(TvPlayerControl.PlayPause),
                    chipLabels = TvPlayerChipLabels(
                        audio = "English",
                        subtitles = "Off",
                        speed = "1x",
                        stream = "Original",
                    ),
                    focused = TvPlayerControl.SeekBar,
                    isReleased = { released },
                )
            }
        }
        compose.settle()
        compose.runOnUiThread {
            released = true
            spy.release()
            show = false
        }
        compose.settle()

        compose.runOnIdle {
            assertEquals(1, spy.releaseCount)
            assertEquals(0, spy.removedAfterRelease)
            assertFalse(spy.accessedAfterRelease)
        }
    }

    @Test
    fun keyEventAfterCoalescedStopBeforeRecompositionDoesNotReachReleasedPlayer() {
        val players = mutableListOf<RejectAfterReleasePlayer>()
        val owner = TestLifecycleOwner()
        val factory = object : TvPlayerFactory {
            override fun create(context: Context, mediaType: PlaybackMediaType): Player =
                RejectAfterReleasePlayer(FakePlayer()).also { players += it }

            override fun publish(context: Context, player: Player): Closeable = Closeable {}
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PutioTvTheme {
                    TvPlayerScreen(
                        state = readyState(resumePositionMillis = 42_000L),
                        onBack = {},
                        onRetry = {},
                        onPlayerFailure = { _, _ -> },
                        playerFactory = factory,
                    )
                }
            }
        }
        compose.settle()
        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
            val key = AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            compose.activity.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, key))
            compose.activity.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, key))
        }
        compose.settle()

        compose.runOnIdle {
            assertTrue(players[0].released)
            assertEquals(1, players[0].releaseCount)
            assertFalse("Key event after stop must not reach released player", players[0].accessedAfterRelease)
        }
    }

    private fun subtitleTrackGroup() = TrackGroup(
        "text-one",
        Format.Builder()
            .setId("t-en")
            .setLanguage("en")
            .setLabel("English SDH")
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .build(),
    )

    private fun addPositiveSubtitleDelay() {
        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
        }
        compose.settle()
        compose.onNode(hasText("Size and timing") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.settle()
        compose.onNode(hasText("+0.1") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.settle()
        compose.back()
        compose.back()
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class RejectAfterReleasePlayer(
        delegate: Player,
    ) : ForwardingPlayer(delegate) {
        var released = false
        var releaseCount = 0
        var removedAfterRelease = 0
        var accessedAfterRelease = false

        override fun release() {
            releaseCount += 1
            released = true
            super.release()
        }

        override fun addListener(listener: Player.Listener) {
            if (released) accessedAfterRelease = true
            super.addListener(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            if (released) removedAfterRelease += 1
            super.removeListener(listener)
        }

        override fun getCurrentPosition(): Long {
            if (released) accessedAfterRelease = true
            return super.getCurrentPosition()
        }

        override fun getDuration(): Long {
            if (released) accessedAfterRelease = true
            return super.getDuration()
        }

        override fun getCurrentMediaItem(): MediaItem? {
            if (released) accessedAfterRelease = true
            return super.getCurrentMediaItem()
        }

        override fun getMediaMetadata(): MediaMetadata {
            if (released) accessedAfterRelease = true
            return super.getMediaMetadata()
        }

        override fun play() {
            if (released) accessedAfterRelease = true
            super.play()
        }

        override fun pause() {
            if (released) accessedAfterRelease = true
            super.pause()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (released) accessedAfterRelease = true
            super.setPlayWhenReady(playWhenReady)
        }
    }

    private companion object {
        const val HELD_REPEATS = 10
    }
}
