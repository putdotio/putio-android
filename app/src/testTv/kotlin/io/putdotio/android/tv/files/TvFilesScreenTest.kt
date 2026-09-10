package io.putdotio.android.tv.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.FilesViewportPosition
import io.putdotio.android.files.FilesSort
import io.putdotio.sdk.errors.PutioConfigurationException
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
class TvFilesScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun rootListsRowsWithSizeAndWatchedProgressAndFocusesTheFirstRow() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(
                        item(1, "tears_of_steel", PutioFileType.FOLDER, sizeBytes = 544_890_000),
                        item(2, "Tears of Steel.webm", PutioFileType.VIDEO, playback = FilesPlaybackProgress(243.0, 734.0)),
                        item(3, "notes.txt", PutioFileType.TEXT, sizeBytes = 1_137),
                    ),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                )
            }
        }

        compose.onNodeWithText("Your Files").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open tears_of_steel").assertIsFocused()
        compose.onNodeWithText("33% watched").assertIsDisplayed()
        compose.onNodeWithText("1.1 kB").assertIsDisplayed()

        compose.onNodeWithContentDescription("Open tears_of_steel").performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.OpenFolder(FilesItemId(1))), events)
    }

    @Test
    fun aPlayableRowPlaysAndAnUnsupportedRowExplainsThenGoesBack() {
        val played = mutableListOf<FilesItem>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(item(2, "clip.mp4", PutioFileType.VIDEO), item(3, "notes.txt", PutioFileType.TEXT)),
                    onEvent = { true },
                    onPlayMedia = { played += it },
                )
            }
        }

        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf(FilesItemId(2)), played.map { it.id })

        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Unsupported file type").assertIsDisplayed()
        compose.onNodeWithText("Go back").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithContentDescription("notes.txt").assertIsDisplayed()
    }

    @Test
    fun hardwareBackClosesTheUnsupportedScreenWithoutPoppingTheFolder() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(item(2, "clip.mp4", PutioFileType.VIDEO), item(3, "notes.txt", PutioFileType.TEXT)),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("notes.txt").performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Unsupported file type").assertIsDisplayed()

        compose.runOnUiThread { pressSystemBack() }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("notes.txt").assertIsFocused()
        assertEquals(emptyList<FilesBrowserEvent>(), events)
    }

    @Test
    fun anEmptyFolderFocusesRefreshSoThePaneStaysReachable() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = state(FilesContent.Empty(FilesPaging.Complete)),
                    onEvent = { true },
                    onPlayMedia = {},
                )
            }
        }
        compose.onNodeWithText("This folder is empty.").assertIsDisplayed()
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused()
        compose.onNode(hasText("Account default") and hasClickAction()).assertIsDisplayed()
    }

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
    fun aRefreshKeepsFocusOnTheHeaderWhileTheFolderReloads() {
        var operation by mutableStateOf<FilesFolderOperation>(FilesFolderOperation.Idle)
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = state(FilesContent.Ready(listOf(item(1, "a.txt", PutioFileType.TEXT)), FilesPaging.Complete), operation = operation),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                )
            }
        }
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        operation = FilesFolderOperation.Loading(
            FilesRequestId(3),
            FilesFolderOperationIntent.Refresh,
            FilesFolderOperationPhase.RELOADING,
        )
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Refresh), events)
    }

    @Test
    fun backFromAFolderReturnsFocusToTheRowThatOpenedIt() {
        val root = ready(
            item(1, "first.txt", PutioFileType.TEXT),
            item(2, "Movies", PutioFileType.FOLDER),
            item(3, "third.txt", PutioFileType.TEXT),
        )
        val child = FilesBrowserState(
            stack = root.stack + FilesFolderState(
                folder = FilesFolder(FilesItemId(2), "Movies"),
                content = FilesContent.Ready(listOf(item(4, "inner.mp4", PutioFileType.VIDEO)), FilesPaging.Complete),
            ),
            nextRequestValue = 11L,
        )
        var state by mutableStateOf(root)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(state = state, onEvent = { true }, onPlayMedia = {})
            }
        }
        compose.onNodeWithContentDescription("first.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Open Movies").assertIsFocused()

        state = child
        compose.onNodeWithContentDescription("Play inner.mp4").assertIsFocused()

        state = root
        compose.onNodeWithContentDescription("Open Movies").assertIsFocused()
    }

    @Test
    fun theSortDialogClosesWhenTheFolderStartsAnOperation() {
        var operation by mutableStateOf<FilesFolderOperation>(FilesFolderOperation.Idle)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = state(FilesContent.Ready(listOf(item(1, "a.txt", PutioFileType.TEXT)), FilesPaging.Complete), operation = operation),
                    onEvent = { true },
                    onPlayMedia = {},
                )
            }
        }
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Refresh") and hasClickAction()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(hasText("Account default") and hasClickAction()).performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Sort by").assertIsDisplayed()

        operation = FilesFolderOperation.Loading(
            FilesRequestId(3),
            FilesFolderOperationIntent.Refresh,
            FilesFolderOperationPhase.RELOADING,
        )
        compose.onAllNodesWithText("Sort by").assertCountEquals(0)
    }

    @Test
    fun aLoadingFolderKeepsFocusInThePane() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(state = state(FilesContent.Loading(FilesRequestId(1))), onEvent = { true }, onPlayMedia = {})
            }
        }
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused()
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

    @Test
    fun headerOffersRefreshAndSortAndTheSortDialogDispatchesTheChoice() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(item(1, "a.txt", PutioFileType.TEXT), sort = FilesSort.NAME_ASCENDING),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                )
            }
        }

        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Refresh), events)

        compose.onNode(hasText("Refresh") and hasClickAction()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(hasText("Name, A–Z") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Sort by").assertIsDisplayed()
        compose.onNode(hasText("Name, A–Z") and hasClickAction() and hasSelectedState()).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Name, Z–A") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(FilesBrowserEvent.SelectSort(FilesSort.NAME_DESCENDING), events.last())
    }

    @Test
    fun failedAndEmptyAndPagingStatesOfferTheRightAction() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = state(FilesContent.Failed(FilesFailure.NetworkUnavailable(PutioConfigurationException("x")))),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                )
            }
        }
        compose.onNodeWithText("Check the network and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Retry), events)
    }

    @Test
    fun aPagedFolderOffersLoadMoreAfterTheLastRow() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(item(1, "a.txt", PutioFileType.TEXT), paging = FilesPaging.Available(FilesCursor("c"))),
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

    private fun hasSelectedState() = androidx.compose.ui.test.isSelected()

    private fun pressSystemBack() {
        compose.activity.onBackPressedDispatcher.onBackPressed()
    }

    private fun networkFailure() = FilesFailure.NetworkUnavailable(PutioConfigurationException("x"))

    private fun ready(
        vararg items: FilesItem,
        sort: FilesSort? = null,
        paging: FilesPaging = FilesPaging.Complete,
    ): FilesBrowserState = state(FilesContent.Ready(items.toList(), paging), sort)

    private fun state(
        content: FilesContent,
        sort: FilesSort? = null,
        operation: FilesFolderOperation = FilesFolderOperation.Idle,
    ): FilesBrowserState =
        FilesBrowserState(
            stack = listOf(FilesFolderState(folder = FilesFolder.Root.copy(sort = sort), content = content, operation = operation)),
            nextRequestValue = 10L,
        )

    private fun item(
        id: Long,
        name: String,
        type: PutioFileType,
        sizeBytes: Long = 128L,
        playback: FilesPlaybackProgress? = null,
    ): FilesItem =
        FilesItem(
            id = FilesItemId(id),
            parentId = FilesFolder.Root.id,
            name = name,
            type = type,
            sizeBytes = sizeBytes,
            createdAt = "2026-04-20T10:00:00Z",
            playback = playback,
        )
}
