package io.putdotio.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import io.putdotio.android.design.LocalBuildDispatcher
import kotlinx.coroutines.Dispatchers

internal fun ComposeContentTestRule.awaitText(text: String, substring: Boolean = false) =
    waitUntil(30_000L) {
        onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

internal fun ComposeContentTestRule.setBuiltContent(content: @Composable () -> Unit) {
    setContent { CompositionLocalProvider(LocalBuildDispatcher provides Dispatchers.Unconfined, content = content) }
}
