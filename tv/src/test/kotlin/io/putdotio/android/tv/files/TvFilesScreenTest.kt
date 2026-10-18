package io.putdotio.android.tv.files

import androidx.compose.runtime.getValue
import io.putdotio.android.tv.pressCenter
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
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
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
import io.putdotio.android.files.FilesSort
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.putdotio.android.files.filesBrowserState

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
        compose.showFiles(
            state = {
                ready(
                    item(1, "tears_of_steel", PutioFileType.FOLDER, sizeBytes = 544_890_000),
                    item(
                        2,
                        "Tears of Steel.webm",
                        PutioFileType.VIDEO,
                        playback = FilesPlaybackProgress(243.0, 734.0),
                    ),
                    item(3, "notes.txt", PutioFileType.TEXT, sizeBytes = 1_137),
                )
            },
            onEvent = { events += it; true },
        )

        compose.onNodeWithText("Your Files").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open tears_of_steel").assertIsFocused()
        compose.onNodeWithText("33% watched").assertIsDisplayed()
        compose.onNodeWithText("1.1 kB").assertIsDisplayed()

        compose.onNodeWithContentDescription("Open tears_of_steel").performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.OpenFolder(FilesItemId(1))), events)
    }

    @Test
    fun aPlayableRowPlaysAndAnUnsupportedRowExplainsThenGoesBack() {
        val played = mutableListOf<FilesItem>()
        compose.showFiles(
            state = { ready(item(2, "clip.mp4", PutioFileType.VIDEO), item(3, "notes.txt", PutioFileType.TEXT)) },
            onPlayMedia = { played += it },
        )

        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressCenter() }
        assertEquals(listOf(FilesItemId(2)), played.map { it.id })

        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("Unsupported file type").assertIsDisplayed()
        compose.onNodeWithText("Go back").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithContentDescription("notes.txt").assertIsDisplayed()
    }

    @Test
    fun hardwareBackClosesTheUnsupportedScreenWithoutPoppingTheFolder() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = { ready(item(2, "clip.mp4", PutioFileType.VIDEO), item(3, "notes.txt", PutioFileType.TEXT)) },
            onEvent = { events += it; true },
        )
        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("notes.txt").performKeyInput { pressCenter() }
        compose.onNodeWithText("Unsupported file type").assertIsDisplayed()

        compose.runOnUiThread { pressSystemBack() }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("notes.txt").assertIsFocused()
        assertEquals(emptyList<FilesBrowserEvent>(), events)
    }

    @Test
    fun anEmptyFolderFocusesRefreshSoThePaneStaysReachable() {
        compose.showFiles(state = { state(FilesContent.Empty(FilesPaging.Complete)) })
        compose.onNodeWithText("This folder is empty.").assertIsDisplayed()
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused()
        compose.onNode(hasText("Account default") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun aRefreshKeepsFocusOnTheHeaderWhileTheFolderReloads() {
        var operation by mutableStateOf<FilesFolderOperation>(FilesFolderOperation.Idle)
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = {
                state(
                    FilesContent.Ready(listOf(item(1, "a.txt", PutioFileType.TEXT)), FilesPaging.Complete),
                    operation = operation,
                )
            },
            onEvent = { events += it; true },
        )
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        operation = FilesFolderOperation.Loading(
            FilesRequestId(3),
            FilesFolderOperationIntent.Refresh,
            FilesFolderOperationPhase.RELOADING,
        )
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Refresh), events)
    }

    @Test
    fun backFromAFolderReturnsFocusToTheRowThatOpenedIt() {
        val root = ready(
            item(1, "first.txt", PutioFileType.TEXT),
            item(2, "Movies", PutioFileType.FOLDER),
            item(3, "third.txt", PutioFileType.TEXT),
        )
        val child = filesBrowserState(
            stack = root.stack + FilesFolderState(
                folder = FilesFolder(FilesItemId(2), "Movies"),
                content = FilesContent.Ready(listOf(item(4, "inner.mp4", PutioFileType.VIDEO)), FilesPaging.Complete),
            ),
            nextRequestValue = 11L,
        )
        var state by mutableStateOf(root)
        compose.showFiles(state = { state })
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
        compose.showFiles(
            state = {
                state(
                    FilesContent.Ready(listOf(item(1, "a.txt", PutioFileType.TEXT)), FilesPaging.Complete),
                    operation = operation,
                )
            },
        )
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Refresh") and hasClickAction()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(hasText("Account default") and hasClickAction()).performKeyInput { pressCenter() }
        compose.onNodeWithText("Sort by").assertIsDisplayed()

        operation = FilesFolderOperation.Loading(
            FilesRequestId(3),
            FilesFolderOperationIntent.Refresh,
            FilesFolderOperationPhase.RELOADING,
        )
        compose.onAllNodesWithText("Sort by").assertCountEquals(0)
    }

    @Test
    fun aFailedFolderKeepsRefreshAndSortInert() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(state = { state(FilesContent.Failed(networkFailure())) }, onEvent = { events += it; true })
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Account default") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
        }
        compose.onAllNodesWithText("Sort by").assertCountEquals(0)
        assertEquals(emptyList<FilesBrowserEvent>(), events)
    }

    @Test
    fun aFailedRefreshShowsTheErrorWithRetryAndKeepsPagingEnabled() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = {
                state(
                    FilesContent.Ready(
                        listOf(item(1, "a.txt", PutioFileType.TEXT)),
                        FilesPaging.Available(FilesCursor("c")),
                    ),
                    operation = FilesFolderOperation.Failed(
                        networkFailure(),
                        FilesFolderOperationIntent.Refresh,
                        FilesFolderOperationPhase.RELOADING,
                    ),
                )
            },
            onEvent = { events += it; true },
        )
        compose.onNodeWithText("Couldn’t refresh files.").assertIsDisplayed()
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Load more").assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.LoadNextPage, FilesBrowserEvent.Retry), events)
    }

    @Test
    fun theSortDialogScrollsAChoiceBelowTheFoldIntoView() {
        compose.showFiles(
            state = { ready(item(1, "a.txt", PutioFileType.TEXT), sort = FilesSort.WATCH_STATUS_DESCENDING) },
        )
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Refresh") and hasClickAction()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(hasText("Watched first") and hasClickAction()).performKeyInput { pressCenter() }
        compose.onNode(hasText("Watched first") and hasClickAction() and hasSelectedState())
            .assertIsFocused()
            .assertIsDisplayed()
    }

    @Test
    fun theUnsupportedScreenDoesNotReturnOnItsOwnAfterARefresh() {
        val listed = ready(item(3, "notes.txt", PutioFileType.TEXT))
        var state by mutableStateOf(listed)
        compose.showFiles(state = { state })
        compose.onNodeWithContentDescription("notes.txt").performKeyInput { pressCenter() }
        compose.onNodeWithText("Unsupported file type").assertIsDisplayed()

        state = state(FilesContent.Loading(FilesRequestId(2)))
        compose.onAllNodesWithText("Unsupported file type").assertCountEquals(0)
        state = listed
        compose.onAllNodesWithText("Unsupported file type").assertCountEquals(0)
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused()
    }

    @Test
    fun aSortThatMovesTheFocusedRowBelowTheFoldKeepsItFocusedAndVisible() {
        val rows = (1..30L).map { item(it, "file-%02d.txt".format(it), PutioFileType.TEXT) }
        var state by mutableStateOf(ready(*rows.toTypedArray()))
        compose.showFiles(state = { state })
        compose.onNodeWithContentDescription("file-01.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("file-02.txt").assertIsFocused()

        state = ready(*rows.reversed().toTypedArray(), sort = FilesSort.NAME_DESCENDING)
        compose.onNodeWithContentDescription("file-02.txt").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun aRefreshThatDropsTheFocusedRowFallsBackToTheFirstRow() {
        var state by mutableStateOf(ready(item(1, "a.txt", PutioFileType.TEXT), item(2, "b.txt", PutioFileType.TEXT)))
        compose.showFiles(state = { state })
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("b.txt").assertIsFocused()

        state = ready(item(1, "a.txt", PutioFileType.TEXT), item(3, "c.txt", PutioFileType.TEXT))
        compose.onNodeWithContentDescription("a.txt").assertIsFocused()
    }

    @Test
    fun aRefreshWhileOnTheHeaderKeepsFocusOnTheHeader() {
        var state by mutableStateOf(ready(item(1, "a.txt", PutioFileType.TEXT), item(2, "b.txt", PutioFileType.TEXT)))
        compose.showFiles(state = { state })
        compose.onNodeWithContentDescription("a.txt").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
        }
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused()

        state = ready(
            item(1, "a.txt", PutioFileType.TEXT),
            item(2, "b.txt", PutioFileType.TEXT),
            item(3, "c.txt", PutioFileType.TEXT),
        )
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun aLoadingFolderKeepsFocusInThePane() {
        compose.showFiles(state = { state(FilesContent.Loading(FilesRequestId(1))) })
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun headerOffersRefreshAndSortAndTheSortDialogDispatchesTheChoice() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = { ready(item(1, "a.txt", PutioFileType.TEXT), sort = FilesSort.NAME_ASCENDING) },
            onEvent = { events += it; true },
        )

        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Refresh), events)

        compose.onNode(hasText("Refresh") and hasClickAction()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(hasText("Name, A–Z") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("Sort by").assertIsDisplayed()
        compose.onNode(hasText("Name, A–Z") and hasClickAction() and hasSelectedState()).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Name, Z–A") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(FilesBrowserEvent.SelectSort(FilesSort.NAME_DESCENDING), events.last())
    }

    @Test
    fun aFailedFolderExplainsAndRetries() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(state = { state(FilesContent.Failed(networkFailure())) }, onEvent = { events += it; true })
        compose.onNodeWithText("Check the network and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Retry), events)
    }

    private fun hasSelectedState() = androidx.compose.ui.test.isSelected()

    private fun pressSystemBack() {
        compose.activity.onBackPressedDispatcher.onBackPressed()
    }
}
