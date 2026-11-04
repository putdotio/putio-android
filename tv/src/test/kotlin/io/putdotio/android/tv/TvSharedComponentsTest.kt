package io.putdotio.android.tv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import io.putdotio.android.R
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
    fun aListRowCentresBothLinesAndGrowsToHoldThemAtDoubleFontScale() {
        var fontScale by mutableStateOf(1f)
        compose.setTvContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                TvListRow(
                    glyph = R.drawable.ic_ph_caret_right,
                    title = ROW_TITLE,
                    onClick = {},
                    modifier = Modifier.testTag(LIST_ROW),
                ) { secondary -> Text(ROW_META, style = TV_LIST_ROW_META, color = secondary) }
            }
        }

        compose.onNodeWithTag(LIST_ROW).assertHeightIsEqualTo(TV_LIST_ROW_HEIGHT)
        assertRowHoldsBothLinesCentred(1f)

        fontScale = 2f
        compose.waitForIdle()

        assertRowHoldsBothLinesCentred(2f)
    }

    @Test
    fun aDetailFactLabelStaysOnOneLineAtDoubleFontScale() {
        compose.setTvContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Column {
                    TvDetailBody(
                        title = ROW_TITLE,
                        meta = null,
                        facts = listOf(TvDetailFact(FACT_LABEL, FACT_VALUE)),
                    ) {}
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(FACT_LABEL, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("fact label wraps", 1, layouts.single().lineCount)
    }

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

        compose.runOnIdle { state.show(TvSnackbarMessage("Saved")) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SNACKBAR_MILLIS / 2 + HALF_SECOND_MILLIS)
        compose.onNodeWithText("Saved").assertExists()

        compose.mainClock.advanceTimeBy(SNACKBAR_MILLIS / 2)
        compose.onNodeWithText("Saved").assertDoesNotExist()
    }

    private fun assertRowHoldsBothLinesCentred(fontScale: Float) {
        val meta = compose.onNodeWithText(ROW_META, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        meta.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(fontScale, layout.layoutInput.density.fontScale)
        assertTrue("meta is cut to ${layout.size.height}px", layout.getLineBottom(0) <= layout.size.height)
        val row = compose.onNodeWithTag(LIST_ROW).getUnclippedBoundsInRoot()
        val top = compose.onNodeWithText(ROW_TITLE, useUnmergedTree = true).getUnclippedBoundsInRoot().top - row.top
        val bottom = row.bottom - meta.getUnclippedBoundsInRoot().bottom
        assertTrue("meta leaves the row", bottom >= 0.dp)
        assertEquals("space above and below the lines", top.value, bottom.value, CENTRE_TOLERANCE_DP)
    }

    private companion object {
        const val FRAME_MILLIS = 16L
        const val HALF_SECOND_MILLIS = 500L
        const val LIST_ROW = "list-row"
        const val ROW_TITLE = "Sintel.2010.1080p.mkv"
        const val ROW_META = "1.1 GB"
        const val FACT_LABEL = "Subtitles"
        const val FACT_VALUE = "English, English · SDH"
        const val CENTRE_TOLERANCE_DP = 1f
        const val SHEET_CONTENT = "sheet-content"
        const val VIEWPORT_WIDTH_DP = 1280f
        const val VIEWPORT_HEIGHT_DP = 720f
    }
}
