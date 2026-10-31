package io.putdotio.android.tv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import io.putdotio.android.design.PutioDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvSharedComponentsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aClosedSideSheetReturnsFocusToTheControlThatOpenedIt() {
        compose.setTvContent {
            var open by remember { mutableStateOf(false) }
            val first = remember { FocusRequester() }
            val opener = remember { FocusRequester() }
            LaunchedEffect(Unit) { first.requestFocus() }
            Column {
                TvButton(onClick = {}, modifier = Modifier.focusRequester(first)) { Text("First") }
                TvButton(onClick = { open = true }, modifier = Modifier.focusRequester(opener)) { Text("Sort") }
            }
            if (open) {
                TvSideSheet(onDismiss = { open = false }, returnFocus = opener, title = "Sort by") { initial ->
                    TvButton(onClick = { open = false }, modifier = Modifier.focusRequester(initial)) { Text("Name") }
                }
            }
        }
        compose.onNodeWithText("First").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Sort").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("Name").assertIsFocused()

        compose.onNodeWithText("Name").performKeyInput { pressCenter() }
        compose.waitForIdle()

        compose.onNodeWithText("Sort").assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-television")
    fun theSideSheetKeepsItsContentInsideTheOverscanSafeAreaOnAWideViewport() {
        compose.setTvContent {
            TvSideSheet(onDismiss = {}, returnFocus = null) { initial ->
                TvButton(onClick = {}, modifier = Modifier.focusRequester(initial).fillMaxWidth()) { Text("Name") }
                Box(Modifier.fillMaxSize().testTag(SHEET_CONTENT))
            }
        }
        compose.waitForIdle()

        val content = compose.onNodeWithTag(SHEET_CONTENT).fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        val safeRight = VIEWPORT_WIDTH_DP * (1 - PutioDesignTokens.tvOverscanX) * density
        val safeBottom = VIEWPORT_HEIGHT_DP * (1 - PutioDesignTokens.tvOverscanY) * density
        assertTrue("right ${content.right} > $safeRight", content.right <= safeRight)
        assertTrue("bottom ${content.bottom} > $safeBottom", content.bottom <= safeBottom)
    }

    @Test
    fun aDestructiveConfirmFocusesItsConfirmAndCancelOnlyDismisses() {
        var confirmed = 0
        var dismissed = 0
        compose.setTvContent {
            TvConfirmDialog(
                title = "Sign out of put.io?",
                message = "You can sign in again any time with a new code.",
                confirmLabel = "Sign out",
                onConfirm = { confirmed += 1 },
                onDismiss = { dismissed += 1 },
                destructive = true,
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("Sign out").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressCenter() }

        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun theSnackbarTakesNoFocusAndLeavesAfterSixSeconds() {
        val state = TvSnackbarState()
        compose.setTvContent {
            val row = remember { FocusRequester() }
            LaunchedEffect(Unit) { row.requestFocus() }
            Column {
                TvButton(onClick = {}, modifier = Modifier.focusRequester(row)) { Text("Row") }
                TvSnackbarHost(state)
            }
        }
        compose.runOnIdle { state.show(TvSnackbarMessage("Moved to Trash", actionLabel = "Undo")) }
        compose.onNodeWithText("Moved to Trash").assertExists()
        compose.onNodeWithText("Row").assertIsFocused()

        compose.mainClock.autoAdvance = false

        compose.mainClock.advanceTimeBy(SNACKBAR_MILLIS + FRAME_MILLIS)
        compose.onNodeWithText("Moved to Trash").assertDoesNotExist()
    }

    @Test
    fun anEqualMessageShownAgainRestartsTheSixSeconds() {
        val state = TvSnackbarState()
        compose.setTvContent { TvSnackbarHost(state) }
        compose.runOnIdle { state.show(TvSnackbarMessage("Saved")) }
        compose.onNodeWithText("Saved").assertExists()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(SNACKBAR_MILLIS / 2)

        compose.runOnUiThread { state.show(TvSnackbarMessage("Saved")) }
        compose.mainClock.advanceTimeBy(SNACKBAR_MILLIS / 2 + FRAME_MILLIS)
        compose.onNodeWithText("Saved").assertExists()

        compose.mainClock.advanceTimeBy(SNACKBAR_MILLIS / 2)
        compose.onNodeWithText("Saved").assertDoesNotExist()
    }

    private companion object {
        const val FRAME_MILLIS = 16L
        const val SHEET_CONTENT = "sheet-content"
        const val VIEWPORT_WIDTH_DP = 1280f
        const val VIEWPORT_HEIGHT_DP = 720f
    }
}
