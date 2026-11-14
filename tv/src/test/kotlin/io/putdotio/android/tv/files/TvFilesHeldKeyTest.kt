package io.putdotio.android.tv.files

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyPress
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesPaging
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesHeldKeyTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun aHeldDownKeepsTheDetailOnItsRowUntilTheKeyIsReleased() {
        val names = (1..5L).map { "file-$it.txt" }
        val rows = names.mapIndexed { index, name -> item(index + 1L, name, PutioFileType.TEXT) }
        compose.showFiles(state = { state(FilesContent.Ready(rows, FilesPaging.Complete)) })

        compose.onNodeWithContentDescription(names[0]).performKeyPress(dpadDown(repeatCount = 0))
        compose.onNodeWithContentDescription(names[1]).assertIsFocused().performKeyPress(dpadDown(repeatCount = 1))
        compose.onNodeWithContentDescription(names[2]).assertIsFocused().performKeyPress(dpadDown(repeatCount = 2))
        val held = compose.onNodeWithContentDescription(names[3]).assertIsFocused()
        compose.onNode(detailTitle(names[1])).assertExists()

        held.performKeyPress(dpadDownReleased())
        compose.onNode(detailTitle(names[3])).assertExists()
    }

    @Test
    fun aHeldDownReportsWhereTheListRestsOnceReleased() {
        val names = (1..40L).map { "file-$it.txt" }
        val rows = names.mapIndexed { index, name -> item(index + 1L, name, PutioFileType.TEXT) }
        val viewports = mutableListOf<FilesBrowserEvent.ViewportChanged>()
        compose.showFiles(
            state = { state(FilesContent.Ready(rows, FilesPaging.Complete)) },
            onEvent = { event ->
                if (event is FilesBrowserEvent.ViewportChanged) viewports += event
                true
            },
        )
        compose.onNodeWithContentDescription(names[0]).performKeyPress(dpadDown(repeatCount = 0))
        for (repeat in 1..15) {
            compose.onNodeWithTag(TV_FILES_LIST_TAG).performKeyPress(dpadDown(repeatCount = repeat))
            compose.waitForIdle()
        }
        compose.onNodeWithContentDescription(names[0]).assertIsNotDisplayed()
        assertEquals(emptyList<FilesBrowserEvent.ViewportChanged>(), viewports)

        compose.onNodeWithTag(TV_FILES_LIST_TAG).performKeyPress(dpadDownReleased())
        compose.waitForIdle()
        assertEquals(1, viewports.size)
    }

    private fun dpadDown(repeatCount: Int) =
        KeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeatCount))

    private fun dpadDownReleased() =
        KeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN, 0))

    private fun detailTitle(name: String) = hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
}
