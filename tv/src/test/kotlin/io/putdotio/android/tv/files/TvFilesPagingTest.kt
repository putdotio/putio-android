package io.putdotio.android.tv.files

import androidx.compose.runtime.getValue
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
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
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
    fun aFailedPageOffersRetryAndLoadingKeepsAFocusOwner() {
        val events = mutableListOf<FilesBrowserEvent>()
        var paging by mutableStateOf<FilesPaging>(FilesPaging.Failed(FilesCursor("c"), networkFailure()))
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(item(1, "a.txt", PutioFileType.TEXT), paging = paging),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                )
            }
        }
        compose.onNodeWithText("Couldn’t load more files.").assertIsDisplayed()
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Retry), events)

        paging = FilesPaging.Loading(FilesCursor("c"), FilesRequestId(9))
        compose.onNodeWithText("Loading more files").assertIsFocused()
    }

    @Test
    fun completingTheLastPageMovesFocusFromLoadMoreToTheLastRow() {
        var content by mutableStateOf<FilesContent>(
            FilesContent.Ready(listOf(item(1, "a.txt", PutioFileType.TEXT)), FilesPaging.Available(FilesCursor("c"))),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(state = state(content), onEvent = { true }, onPlayMedia = {})
            }
        }
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Load more").assertIsFocused()

        content = FilesContent.Ready(
            listOf(item(1, "a.txt", PutioFileType.TEXT), item(2, "b.txt", PutioFileType.TEXT)),
            FilesPaging.Complete,
        )
        compose.onNodeWithContentDescription("b.txt").assertIsFocused()
    }

    @Test
    fun completingAPageWithRowsBelowTheFoldScrollsTheNewLastRowIntoFocus() {
        val firstPage = (1..8L).map { item(it, "file-$it.txt", PutioFileType.TEXT) }
        var content by mutableStateOf<FilesContent>(
            FilesContent.Ready(firstPage, FilesPaging.Available(FilesCursor("c"))),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(state = state(content), onEvent = { true }, onPlayMedia = {})
            }
        }
        compose.onNodeWithContentDescription("file-1.txt").performKeyInput { repeat(8) { pressKey(Key.DirectionDown) } }
        compose.onNodeWithText("Load more").assertIsFocused()

        content = FilesContent.Ready(
            firstPage + (9..30L).map { item(it, "file-$it.txt", PutioFileType.TEXT) },
            FilesPaging.Complete,
        )
        compose.onNodeWithContentDescription("file-30.txt").assertIsFocused()
    }

    @Test
    fun anEmptyPageThatFailedOffersRetryWithFocus() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = state(FilesContent.Empty(FilesPaging.Failed(FilesCursor("c"), networkFailure()))),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                )
            }
        }
        compose.onNodeWithText("Couldn’t load more files.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Retry), events)
    }

    @Test
    fun aMiddlePageArrivingKeepsFocusOnLoadMore() {
        var state by mutableStateOf(
            ready(item(1, "a.txt", PutioFileType.TEXT), paging = FilesPaging.Available(FilesCursor("c1"))),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(state = state, onEvent = { true }, onPlayMedia = {})
            }
        }
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Load more").assertIsFocused()

        state = ready(
            item(1, "a.txt", PutioFileType.TEXT),
            item(2, "b.txt", PutioFileType.TEXT),
            paging = FilesPaging.Available(FilesCursor("c2")),
        )
        compose.onNodeWithText("Load more").assertIsFocused()
    }

    @Test
    fun aPagedFolderOffersLoadMoreAfterTheLastRow() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(
                        item(1, "a.txt", PutioFileType.TEXT),
                        paging = FilesPaging.Available(FilesCursor("c")),
                    ),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                )
            }
        }
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Load more").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.LoadNextPage), events)
    }
}
