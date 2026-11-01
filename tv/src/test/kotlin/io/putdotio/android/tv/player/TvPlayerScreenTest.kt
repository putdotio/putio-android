package io.putdotio.android.tv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.view.KeyEvent as AndroidKeyEvent
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.tv.setTvContent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.playback.PlaybackContent
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
    fun readySourcePlaysAndCenterTogglesPauseWithTheControlsShown() {
        val player = FakePlayer()
        compose.mainClock.autoAdvance = false
        compose.setTvContent {
            TvPlayerScreen(
                state = readyState(resumePositionMillis = 42_000L),
                onBack = {},
                onRetry = {},
                onResume = {},
                onRestart = {},
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
                    onResume = {},
                    onRestart = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> FakePlayer().also { players += it } },
                )
            }
        }
        compose.runOnIdle { players.single().advanceTo(61_000L) }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnIdle { assertFalse(players.single().playWhenReady) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            val (first, recreated) = players
            assertTrue(first.released)
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
                onResume = {},
                onRestart = {},
                onPlayerFailure = { failure, position -> reported = failure to position },
                playerFactory = { _, _ -> player },
            )
        }
        compose.runOnIdle { player.fail(positionMillis = 61_000L) }
        compose.waitForIdle()
        val (failure, position) = checkNotNull(reported)
        assertEquals(61_000L, position)

        state = state.copyForTest(content = PlaybackContent.Failed(failure))
        compose.onNodeWithText("Couldn't play this file.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun quickRightPressesScrubPausedAndCenterPlaysFromTheTarget() {
        val player = FakePlayer()
        compose.showReady(player, resumePositionMillis = 42_000L)

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
            advanceEventTime(TV_SCRUB_ACCUMULATE_MILLIS / 2)
            pressKey(Key.DirectionRight)
        }
        compose.settle()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("1:27")
        compose.runOnIdle {
            assertFalse("Scrubbing pauses", player.playWhenReady)
            assertEquals("Nothing seeks before the commit", 42_000L, player.currentPosition)
        }
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS * 2)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertIsDisplayed()

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.settle()
        compose.runOnIdle {
            assertEquals(87_000L, player.currentPosition)
            assertTrue("The commit resumes playback", player.playWhenReady)
        }
    }

    @Test
    fun pressesAfterAPauseRestartTheStepFromThePendingTarget() {
        val player = FakePlayer()
        compose.showReady(player, resumePositionMillis = 42_000L)

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput {
            pressKey(Key.MediaFastForward)
            advanceEventTime(TV_SCRUB_ACCUMULATE_MILLIS * 2)
            pressKey(Key.MediaFastForward)
            advanceEventTime(TV_SCRUB_ACCUMULATE_MILLIS * 2)
            pressKey(Key.MediaRewind)
        }
        compose.settle()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("0:57")
    }

    @Test
    fun backDismissesSeekModeThenTheControlsKeepingTimeAndFocusThenExitsOnce() {
        val player = FakePlayer()
        var backs = 0
        compose.showReady(player, resumePositionMillis = 42_000L, onBack = { backs += 1 })

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionLeft)
        }
        compose.settle()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("0:27")
        compose.runOnIdle { assertFalse(player.playWhenReady) }

        compose.back()
        compose.runOnIdle {
            assertEquals(0, backs)
            assertEquals("Dismissing seek mode never seeks", 42_000L, player.currentPosition)
            assertTrue("It resumes the playback the scrub paused", player.playWhenReady)
        }
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("0:42")

        compose.back()
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        compose.runOnIdle {
            assertEquals(0, backs)
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

    private companion object {
        const val HELD_REPEATS = 10
    }
}
