package io.putdotio.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.tv.TvDestination
import io.putdotio.sdk.files.PutioFileType

internal fun row(id: Long, name: String) = FilesItem(
    id = FilesItemId(id),
    parentId = FilesFolder.Root.id,
    name = name,
    type = PutioFileType.TEXT,
    sizeBytes = 1L,
    createdAt = "2026-04-20T10:00:00Z",
)

internal fun SemanticsNodeInteractionsProvider.onTab(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

@OptIn(ExperimentalTestApi::class)
internal fun SemanticsNodeInteractionsProvider.openTab(destination: TvDestination) {
    onTab("Home").assertIsFocused().performKeyInput {
        repeat(destination.ordinal) { pressKey(Key.DirectionRight) }
    }
    onTab(destination.name).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
}

internal fun AndroidComposeTestRule<*, ComponentActivity>.pressBack() {
    runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
}
