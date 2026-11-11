package io.putdotio.android.tv.files

import androidx.compose.runtime.getValue
import io.putdotio.android.tv.pressCenter
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesRequestId
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesPagingTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun aFailedPageRetriesOnlyFromTryAgainAndHandsFocusBackToTheRow() {
        val events = mutableListOf<FilesBrowserEvent>()
        var paging by mutableStateOf<FilesPaging>(FilesPaging.Failed(FilesCursor("c"), networkFailure()))
        compose.showFiles(
            state = { ready(item(1, "a.txt", PutioFileType.TEXT), paging = paging) },
            onEvent = { events += it; true },
        )
        compose.onNodeWithText("Couldn’t load more files.").assertIsDisplayed()
        compose.onNodeWithContentDescription("a.txt").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(emptyList<FilesBrowserEvent>(), events)
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Retry), events)

        paging = FilesPaging.Loading(FilesCursor("c"), FilesRequestId(9))
        compose.onNodeWithContentDescription("Loading more files").assertIsDisplayed()
        compose.onNodeWithContentDescription("a.txt").assertIsFocused()
    }

    @Test
    fun rowsNearTheEndLoadTheNextPageOnceAndFocusStaysOnTheRow() {
        val events = mutableListOf<FilesBrowserEvent>()
        var content by mutableStateOf<FilesContent>(
            FilesContent.Ready(listOf(item(1, "a.txt", PutioFileType.TEXT)), FilesPaging.Available(FilesCursor("c"))),
        )
        compose.showFiles(state = { state(content) }, onEvent = { events += it; true })
        compose.onNodeWithContentDescription("a.txt").assertIsFocused()
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.LoadNextPage), events)

        content = FilesContent.Ready(
            listOf(item(1, "a.txt", PutioFileType.TEXT)),
            FilesPaging.Loading(FilesCursor("c"), FilesRequestId(9)),
        )
        compose.onNodeWithContentDescription("Loading more files").assertIsDisplayed()
        compose.onNodeWithContentDescription("a.txt").performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        compose.onNodeWithContentDescription("a.txt").assertIsFocused()

        content = FilesContent.Ready(
            listOf(item(1, "a.txt", PutioFileType.TEXT), item(2, "b.txt", PutioFileType.TEXT)),
            FilesPaging.Complete,
        )
        compose.onNodeWithContentDescription("b.txt").assertIsDisplayed()
        compose.onNodeWithContentDescription("a.txt").assertIsFocused()
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.LoadNextPage), events)
    }

    @Test
    fun aLongListLoadsOnlyWhenItsEndComesNearAndAHeldDownStaysOnTheLastRow() {
        val events = mutableListOf<FilesBrowserEvent>()
        val rows = (1..30L).map { item(it, "file-$it.txt", PutioFileType.TEXT) }
        compose.showFiles(
            state = { state(FilesContent.Ready(rows, FilesPaging.Available(FilesCursor("c")))) },
            onEvent = { events += it; true },
        )
        compose.onNodeWithContentDescription("file-1.txt").assertIsFocused()
        assertEquals(0, events.count { it == FilesBrowserEvent.LoadNextPage })

        compose.onNodeWithContentDescription("file-1.txt").performKeyInput {
            repeat(35) { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithContentDescription("file-30.txt").assertIsFocused()
        assertEquals(1, events.count { it == FilesBrowserEvent.LoadNextPage })
    }

    @Test
    fun anEmptyPageThatFailedOffersRetryWithFocus() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = { state(FilesContent.Empty(FilesPaging.Failed(FilesCursor("c"), networkFailure()))) },
            onEvent = { events += it; true },
        )
        compose.onNodeWithText("Couldn’t load more files.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Retry), events)
    }

    @Test
    fun anEmptyPageWithMoreLoadsItOnItsOwn() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = { state(FilesContent.Empty(FilesPaging.Available(FilesCursor("c")))) },
            onEvent = { events += it; true },
        )
        compose.waitForIdle()
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.LoadNextPage), events)
    }
}
