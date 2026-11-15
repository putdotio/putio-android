package io.putdotio.android

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText

internal fun ComposeContentTestRule.awaitText(text: String, substring: Boolean = false) =
    waitUntil(5_000L) {
        onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
