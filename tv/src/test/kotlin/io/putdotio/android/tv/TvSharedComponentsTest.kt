package io.putdotio.android.tv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The side sheet, confirm dialog and snackbar that every TV screen shares (DESIGN.md › TV components). */
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

    private companion object {
        const val FRAME_MILLIS = 16L
    }
}
