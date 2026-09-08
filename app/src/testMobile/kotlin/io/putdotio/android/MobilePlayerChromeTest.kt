package io.putdotio.android

import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
    fun accessibilityProgressActionSeeksOnceAndReportsTheNewPosition() {
        val player = readyPlayer()
        var scrubs = 0
        compose.setContent {
            PutioTheme { MobileProgressSlider(player, onScrub = { scrubs += 1 }) }
        }
        compose.onNodeWithTag(TIMELINE_TAG).performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue(setProgress(0.75f))
        }
        compose.runOnIdle {
            assertEquals(listOf(45_000L), player.seekPositions)
            assertEquals(1, scrubs)
        }
        compose.onNodeWithTag(TIMELINE_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "00:45 of 01:00"))
    }

    @Test
    fun shortVideoWindowKeepsAllControlsReachableAtDoubleFontScale() {
        val player = readyPlayer()
        var pixelsPerDp = 1f
        compose.setContent {
            val density = LocalDensity.current.density
            pixelsPerDp = density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                PutioTheme {
                    Box(Modifier.size(width = 320.dp, height = 180.dp)) {
                        MobilePlayerChrome(
                            player = player,
                            title = "Video with a long filename.mp4",
                            isAudio = false,
                            visible = true,
                            seekEnabled = true,
                            onSeek = {},
                            onScrub = {},
                            settings = {
                                MobilePlaybackOptions(player, {}, {}, {}, {}, directControls = true)
                                MobileSubtitleControls(
                                    player, player.trackSelectionParameters, {}, {}, {}, {}, showLabel = true,
                                )
                            },
                        )
                    }
                }
            }
        }
        val controls = listOf(
            compose.onNodeWithContentDescription("Back"),
            compose.onNodeWithContentDescription("Back 10 seconds"),
            compose.onNodeWithContentDescription("Play"),
            compose.onNodeWithContentDescription("Forward 10 seconds"),
            compose.onNodeWithTag(TIMELINE_TAG),
            compose.onNodeWithText("Audio"),
            compose.onNodeWithText("Speed (1×)"),
            compose.onNodeWithText("Captions"),
        )
        val bounds = controls.map { it.getUnclippedBoundsInRoot() }
        bounds.forEachIndexed { index, first ->
            bounds.drop(index + 1).forEach { second ->
                assertTrue(
                    "Controls must not overlap: $first and $second",
                    first.right <= second.left || second.right <= first.left ||
                        first.bottom <= second.top || second.bottom <= first.top,
                )
            }
        }
        controls.forEach { control ->
            control.performScrollTo().assertIsDisplayed()
            val node = control.fetchSemanticsNode()
            if (node.config.contains(SemanticsActions.OnClick)) {
                val touchBounds = node.touchBoundsInRoot
                assertTrue(
                    "Button must have a 48dp target: $touchBounds ${node.config}",
                    touchBounds.height >= 48f * pixelsPerDp,
                )
            }
        }
        controls.first().performScrollTo().assertIsDisplayed()
    }

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
    fun focusLossDiscardsKeyboardPreviewAndOrphanReleaseDoesNotSeek() {
        val player = readyPlayer()
        lateinit var inputMode: InputModeManager
        lateinit var focusManager: FocusManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            PutioTheme { MobileProgressSlider(player) }
        }
        focusTimeline { inputMode }
        compose.onNodeWithTag(TIMELINE_TAG).performKeyInput { keyDown(Key.DirectionRight) }
        compose.runOnIdle {
            assertTrue(player.seekPositions.isEmpty())
            focusManager.clearFocus(force = true)
            player.movePositionTo(45_000L)
        }
        compose.onNodeWithTag(TIMELINE_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "00:45 of 01:00"))
        focusTimeline { inputMode }
        compose.onNodeWithTag(TIMELINE_TAG).performKeyInput { keyUp(Key.DirectionRight) }
        compose.runOnIdle {
            assertTrue(player.seekPositions.isEmpty())
            assertEquals(45_000L, player.currentPosition)
        }
    }

    @Test
    fun cancelledPointerDragDiscardsPreviewAndNextAdjustmentStartsAtLivePosition() {
        val player = readyPlayer()
        var scrubs = 0
        lateinit var inputMode: InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            PutioTheme { MobileProgressSlider(player, onScrub = { scrubs += 1 }) }
        }
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput {
            down(center)
            moveTo(Offset(width * 0.2f, center.y))
        }
        compose.runOnIdle {
            assertTrue(scrubs > 0)
            assertTrue(player.seekPositions.isEmpty())
        }
        compose.onNodeWithTag(TIMELINE_TAG).performTouchInput { cancel() }
        compose.runOnIdle {
            assertTrue(player.seekPositions.isEmpty())
            player.movePositionTo(45_000L)
        }
        compose.onNodeWithTag(TIMELINE_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "00:45 of 01:00"))
        focusTimeline { inputMode }
        compose.onNodeWithTag(TIMELINE_TAG).performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        compose.runOnIdle {
            assertEquals(1, player.seekPositions.size)
            assertTrue(player.seekPositions.single() in 45_001L..60_000L)
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
