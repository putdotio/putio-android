package io.putdotio.android

import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
@UnstableApi
class MobilePlayerChromeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun orphanKeyReleaseDoesNotSeekToZeroOrAnOldScrubPosition() {
        val player = readyPlayer()
        var scrubs = 0
        lateinit var host: View
        lateinit var inputMode: InputModeManager
        compose.setContent {
            host = LocalView.current
            inputMode = LocalInputModeManager.current
            PutioTheme { MobileProgressSlider(player, onScrub = { scrubs += 1 }) }
        }
        focusTimeline { inputMode }

        compose.runOnIdle {
            host.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_RIGHT))
            assertTrue(player.seekPositions.isEmpty())
            assertEquals(0, scrubs)
        }
        compose.onNodeWithTag(TIMELINE_TAG).performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        compose.runOnIdle {
            assertEquals(1, player.seekPositions.size)
            player.movePositionTo(45_000L)
        }
        compose.runOnIdle {
            host.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_RIGHT))
            assertEquals(1, player.seekPositions.size)
            assertEquals(45_000L, player.currentPosition)
            assertEquals(1, scrubs)
        }
    }

    @Test
    fun keyboardAdjustmentCommitsOneSeekOnReleaseAndReportsScrubbing() {
        val player = readyPlayer()
        var scrubs = 0
        lateinit var inputMode: InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            PutioTheme { MobileProgressSlider(player, onScrub = { scrubs += 1 }) }
        }
        focusTimeline { inputMode }

        compose.onNodeWithTag(TIMELINE_TAG).performKeyInput { keyDown(Key.DirectionRight) }
        compose.runOnIdle {
            assertTrue(player.seekPositions.isEmpty())
            assertEquals(1, scrubs)
        }
        compose.onNodeWithTag(TIMELINE_TAG).performKeyInput { keyUp(Key.DirectionRight) }
        compose.runOnIdle {
            assertEquals(1, player.seekPositions.size)
            assertTrue(player.seekPositions.single() in 30_001L..60_000L)
            assertEquals(1, scrubs)
        }
    }

    @Test
    fun unknownDurationDisablesScrubbingAndDoesNotReportAnInteraction() {
        val player = readyPlayer(durationMillis = 0L)
        var scrubs = 0
        compose.setContent {
            PutioTheme { MobileProgressSlider(player, onScrub = { scrubs += 1 }) }
        }

        compose.onNodeWithTag(TIMELINE_TAG).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertTrue(player.seekPositions.isEmpty())
            assertEquals(0, scrubs)
        }
    }

    private fun readyPlayer(durationMillis: Long = 60_000L): RecordingPlayer =
        RecordingPlayer(durationMillis = durationMillis).apply {
            setMediaItem(MediaItem.Builder().setMediaId("timeline-test").build())
            prepare()
            movePositionTo(30_000L)
        }

    private fun focusTimeline(inputMode: () -> InputModeManager) {
        compose.runOnIdle { assertTrue(inputMode().requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithTag(TIMELINE_TAG)
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
    }

    private companion object {
        const val TIMELINE_TAG = "mobile-player-timeline"
    }
}
