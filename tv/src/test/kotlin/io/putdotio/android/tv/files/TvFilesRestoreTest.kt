package io.putdotio.android.tv.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesViewportPosition
import io.putdotio.sdk.files.PutioFileType
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
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesRestoreTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun returningToTheMountOffsetIsReportedAgain() {
        val rows = (1..40L).map { item(it, "file-$it.txt", PutioFileType.TEXT) }
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(state = ready(*rows.toTypedArray()), onEvent = { events += it; true }, onPlayMedia = {})
            }
        }
        compose.onNodeWithContentDescription("file-1.txt").assertIsFocused().performKeyInput {
            repeat(12) { pressKey(Key.DirectionDown) }
        }
        compose.waitForIdle()
        val scrolled = events.filterIsInstance<FilesBrowserEvent.ViewportChanged>().last()
        assertTrue(scrolled.position.firstVisibleItemIndex > 0)
        compose.onNodeWithContentDescription("file-13.txt").performKeyInput { repeat(12) { pressKey(Key.DirectionUp) } }
        compose.waitForIdle()
        assertEquals(
            FilesViewportPosition(),
            events.filterIsInstance<FilesBrowserEvent.ViewportChanged>().last().position,
        )
    }

    @Test
    fun aProgrammaticScrollToTheFocusedRowReportsTheViewport() {
        val rows = (1..40L).map { item(it, "file-$it.txt", PutioFileType.TEXT) }
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = state(FilesContent.Ready(rows, FilesPaging.Complete, FilesViewportPosition(30, 0))),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                )
            }
        }
        compose.onNodeWithContentDescription("file-1.txt").assertIsFocused()
        assertEquals(
            listOf<FilesBrowserEvent>(FilesBrowserEvent.ViewportChanged(FilesViewportPosition(0, 0))),
            events,
        )
    }

    @Test
    fun focusMemoryOutlivesThePaneWhenItIsHostedBySomeoneElse() {
        val memory = mutableMapOf<Long, Long>()
        var shown by mutableStateOf(true)
        val root = ready(item(1, "first.txt", PutioFileType.TEXT), item(2, "second.txt", PutioFileType.TEXT))
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                if (shown) TvFilesScreen(state = root, onEvent = { true }, onPlayMedia = {}, focusMemory = memory)
            }
        }
        compose.onNodeWithContentDescription("first.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("second.txt").assertIsFocused()

        shown = false
        compose.waitForIdle()
        shown = true
        compose.onNodeWithContentDescription("second.txt").assertIsFocused()
    }

    @Test
    fun loadMoreFocusOutlivesThePaneWhenItIsRemounted() {
        val memory = mutableMapOf<Long, Long>()
        var shown by mutableStateOf(true)
        val paged = ready(item(1, "first.txt", PutioFileType.TEXT), paging = FilesPaging.Available(FilesCursor("c")))
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                if (shown) TvFilesScreen(state = paged, onEvent = { true }, onPlayMedia = {}, focusMemory = memory)
            }
        }
        compose.onNodeWithContentDescription("first.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Load more").assertIsFocused()

        shown = false
        compose.waitForIdle()
        shown = true
        compose.onNodeWithText("Load more").assertIsFocused()
    }

    @Test
    fun aRemountAfterTheLastPageLandedFocusesTheLastRow() {
        val memory = mutableMapOf<Long, Long>()
        var shown by mutableStateOf(true)
        var state by mutableStateOf(
            ready(item(1, "first.txt", PutioFileType.TEXT), paging = FilesPaging.Available(FilesCursor("c"))),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                if (shown) TvFilesScreen(state = state, onEvent = { true }, onPlayMedia = {}, focusMemory = memory)
            }
        }
        compose.onNodeWithContentDescription("first.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Load more").assertIsFocused()

        shown = false
        compose.waitForIdle()
        state = ready(item(1, "first.txt", PutioFileType.TEXT), item(2, "second.txt", PutioFileType.TEXT))
        shown = true
        compose.onNodeWithContentDescription("second.txt").assertIsFocused()
    }

    @Test
    fun aRestoredViewportWithoutFocusMemoryScrollsToAndFocusesTheFirstRow() {
        val rows = (1..40L).map { item(it, "file-$it.txt", PutioFileType.TEXT) }
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = state(FilesContent.Ready(rows, FilesPaging.Complete, FilesViewportPosition(30, 0))),
                    onEvent = { true },
                    onPlayMedia = {},
                )
            }
        }
        compose.onNodeWithContentDescription("file-1.txt").assertIsFocused()
    }
}
