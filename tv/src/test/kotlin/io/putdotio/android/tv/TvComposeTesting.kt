package io.putdotio.android.tv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import io.putdotio.android.design.PutioTvTheme

internal fun ComposeContentTestRule.awaitText(text: String, substring: Boolean = false) =
    waitUntil(30_000L) {
        onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

/** [content] in the app's Compose for TV theme. */
internal fun ComposeContentTestRule.setTvContent(content: @Composable () -> Unit) {
    setContent { PutioTvTheme(content = content) }
}

/** A D-pad Center tap with no hold between down and up. */
@OptIn(ExperimentalTestApi::class)
internal fun KeyInjectionScope.pressCenter() {
    keyDown(Key.DirectionCenter)
    keyUp(Key.DirectionCenter)
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.assertHeldDownWaitsForScroll(listTag: String, rows: List<SemanticsMatcher>) {
    val listBottom = onNodeWithTag(listTag).fetchSemanticsNode().boundsInRoot.bottom
    val lastWhole = rows.indexOfLast { row ->
        onAllNodes(row).fetchSemanticsNodes().any { it.positionInRoot.y + it.size.height <= listBottom }
    }
    onNode(rows[0]).assertIsFocused().performKeyInput {
        repeat(lastWhole) { pressKey(Key.DirectionDown) }
    }
    val lastShown = onNode(rows[lastWhole]).assertIsFocused()
    val hidden = onNode(rows[lastWhole + 1])

    mainClock.autoAdvance = false
    lastShown.performKeyPress(dpadDown(repeatCount = 0))
    hidden.assertIsFocused().performKeyPress(dpadDown(repeatCount = 1))
    hidden.assertIsFocused()

    mainClock.autoAdvance = true
    hidden.performKeyPress(dpadDown(repeatCount = 2))
    onNode(rows[lastWhole + 2]).assertIsFocused()
}

private fun dpadDown(repeatCount: Int) =
    KeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeatCount))
