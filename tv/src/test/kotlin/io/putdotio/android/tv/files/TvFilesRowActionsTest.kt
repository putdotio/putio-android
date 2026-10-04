package io.putdotio.android.tv.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.FilesDeleteStatus
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesRequestId
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.putdotio.android.files.copyForTest

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesRowActionsTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun theMenuKeyOpensTheRowActionsAndMoveToTrashRunsWithoutAConfirmation() {
        val events = mutableListOf<FilesBrowserEvent>()
        val toggled = mutableListOf<Pair<Long, Boolean>>()
        val opened = mutableListOf<Long>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(
                        item(2, "clip.mp4", PutioFileType.VIDEO, playback = FilesPlaybackProgress(0.0, 120.0)),
                        item(3, "notes.txt", PutioFileType.TEXT),
                    ),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                    confirmedTrashEnabled = true,
                    watchedToggleEnabled = true,
                    onOpenInVlc = { opened += it.id.value },
                    onSetWatched = { item, watched -> toggled += item.id.value to watched },
                )
            }
        }

        compose.onNodeWithContentDescription("Play clip.mp4").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Open in VLC").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Mark as watched").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf(2L to true), toggled)
        compose.onNodeWithContentDescription("Play clip.mp4").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Open in VLC").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithText("Move to trash").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onAllNodesWithText("Cancel").assertCountEquals(0)
        assertEquals(
            listOf<FilesBrowserEvent>(
                FilesBrowserEvent.Delete(FilesFolder.Root.id, FilesItemId(2), FilesDeleteMode.TRASH),
            ),
            events,
        )
        compose.onNodeWithContentDescription("Play clip.mp4").assertIsFocused()
        assertEquals(emptyList<Long>(), opened)
    }

    @Test
    fun permanentDeletionConfirmsWithCancelFocusedBeforeDispatching() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(item(3, "notes.txt", PutioFileType.TEXT)),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                    confirmedTrashEnabled = false,
                )
            }
        }

        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Delete permanently").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Delete permanently?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<FilesBrowserEvent>(), events) }
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Delete permanently").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.runOnIdle {
            assertEquals(
                listOf<FilesBrowserEvent>(
                    FilesBrowserEvent.Delete(FilesFolder.Root.id, FilesItemId(3), FilesDeleteMode.PERMANENT),
                ),
                events,
            )
        }
        compose.onAllNodesWithText("Delete permanently?").assertCountEquals(0)
    }

    @Test
    fun aTrashPressMadeBeforeTrashTurnedOffReachedTheMenuSendsNothing() {
        val events = mutableListOf<FilesBrowserEvent>()
        var trash by mutableStateOf<Boolean?>(true)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(item(3, "notes.txt", PutioFileType.TEXT)),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                    confirmedTrashEnabled = trash,
                )
            }
        }

        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Move to trash").assertIsFocused()
        compose.mainClock.autoAdvance = false
        trash = false
        compose.onNodeWithText("Move to trash").performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(emptyList<FilesBrowserEvent>(), events) }
        compose.onNodeWithText("Delete permanently").assertIsFocused()
    }

    @Test
    fun aTextRowOffersOnlyDeletionAndNothingWithoutTheTrashSetting() {
        var trash by mutableStateOf<Boolean?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(item(3, "notes.txt", PutioFileType.TEXT)),
                    onEvent = { true },
                    onPlayMedia = {},
                    confirmedTrashEnabled = trash,
                )
            }
        }

        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onAllNodesWithText("Cancel").assertCountEquals(0)
        compose.runOnIdle { trash = false }
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Delete permanently").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onAllNodesWithText("Mark as watched").assertCountEquals(0)
        // The setting flipping under an open confirmation withdraws it rather than rewording it.
        compose.onNodeWithText("Delete permanently?").assertIsDisplayed()
        compose.runOnIdle { trash = true }
        compose.onAllNodesWithText("Delete permanently?").assertCountEquals(0)
        compose.onNodeWithText("Move to trash").assertIsFocused()
    }

    @Test
    fun sharedItemsOfferNeitherTheWatchedToggleNorDeletion() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(
                        item(2, "clip.mp4", PutioFileType.VIDEO, playback = FilesPlaybackProgress(0.0, 120.0))
                            .copy(isShared = true),
                        item(3, "Items shared with you", PutioFileType.FOLDER)
                            .copy(folderType = PutioFolderType.SHARED_ROOT),
                    ),
                    onEvent = { true },
                    onPlayMedia = {},
                    confirmedTrashEnabled = true,
                    watchedToggleEnabled = true,
                )
            }
        }

        compose.onNodeWithContentDescription("Play clip.mp4").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Open in VLC").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Cancel").assertIsFocused()
        compose.onAllNodesWithText("Mark as watched").assertCountEquals(0)
        compose.onAllNodesWithText("Move to trash").assertCountEquals(0)
        compose.onNodeWithText("Cancel").performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithContentDescription("Play clip.mp4").assertIsFocused()
    }

    @Test
    fun aSharedFolderOpensNoMenu() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = ready(
                        item(3, "Items shared with you", PutioFileType.FOLDER)
                            .copy(folderType = PutioFolderType.SHARED_ROOT),
                    ),
                    onEvent = { true },
                    onPlayMedia = {},
                    confirmedTrashEnabled = true,
                )
            }
        }

        compose.onNodeWithContentDescription("Open Items shared with you").assertIsFocused().performKeyInput {
            pressKey(Key.Menu)
        }
        compose.onAllNodesWithText("Cancel").assertCountEquals(0)
        compose.onNodeWithContentDescription("Open Items shared with you").assertIsFocused()
    }

    @Test
    fun aRunningDeleteShowsItsPhaseAndAFailedCheckOffersCheckStatus() {
        val events = mutableListOf<FilesBrowserEvent>()
        val intent = FilesFolderOperationIntent.Delete(FilesItemId(2), FilesDeleteMode.TRASH)
        var state by mutableStateOf(
            state(
                FilesContent.Ready(listOf(item(2, "clip.mp4", PutioFileType.VIDEO)), FilesPaging.Complete),
                operation = FilesFolderOperation.Loading(FilesRequestId(3), intent, FilesFolderOperationPhase.DELETING),
            ),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(state = state, onEvent = { events += it; true }, onPlayMedia = {})
            }
        }

        compose.onNodeWithText("Moving to trash").assertIsDisplayed()
        compose.runOnIdle {
            state = state(
                FilesContent.Ready(listOf(item(2, "clip.mp4", PutioFileType.VIDEO)), FilesPaging.Complete),
                operation = FilesFolderOperation.Failed(
                    networkFailure(),
                    intent,
                    FilesFolderOperationPhase.CHECKING_DELETE,
                ),
            )
        }
        compose.onNodeWithText("Couldn’t confirm the result. Check the item before trying again.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Check status") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Retry), events)
    }

    @Test
    fun aFolderTooLargeForTrashExplainsAndDeletesPermanentlyOnlyAfterConfirmation() {
        val events = mutableListOf<FilesBrowserEvent>()
        val folder = item(4, "Sample folder", PutioFileType.FOLDER)
        val outcome = FilesDeleteOutcome(
            FilesRequestId(5), FilesFolderOperationIntent.Delete(folder.id, FilesDeleteMode.TRASH), folder.name,
            status = FilesDeleteStatus.TOO_LARGE_FOR_TRASH,
        )
        fun withOutcome(listed: FilesItem) =
            ready(listed).let { it.copyForTest(stack = listOf(it.current.copy(deleteOutcome = outcome))) }
        var state by mutableStateOf(withOutcome(folder))
        setConfirmedTrashScreen({ state }, events)
        val message = "This folder contains too many files. Would you want to delete it PERMANENTLY?"

        compose.onNodeWithText("We couldn’t send these files to trash").assertIsDisplayed()
        compose.onAllNodesWithText("Sample folder is still in Files. Open its actions to try again.")
            .assertCountEquals(0)
        compose.onNodeWithContentDescription("Open Sample folder").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNode(hasText("Delete permanently") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onAllNodesWithText(message).assertCountEquals(0)
        compose.runOnIdle { assertEquals(emptyList<FilesBrowserEvent>(), events) }

        compose.onNode(hasText("Delete permanently") and hasClickAction()).performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Delete").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.runOnIdle {
            assertEquals(
                listOf<FilesBrowserEvent>(
                    FilesBrowserEvent.Delete(FilesFolder.Root.id, folder.id, FilesDeleteMode.PERMANENT),
                ),
                events,
            )
        }
        compose.onAllNodesWithText(message).assertCountEquals(0)

        // A rename keeps the outcome; the confirmation names the folder as it is listed now.
        compose.runOnIdle { state = withOutcome(folder.copy(name = "Renamed folder")) }
        compose.onNode(hasText("Delete permanently") and hasClickAction()).apply {
            performSemanticsAction(SemanticsActions.RequestFocus)
            assertIsFocused()
            performKeyInput {
                keyDown(Key.DirectionCenter)
                keyUp(Key.DirectionCenter)
            }
        }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onAllNodesWithText("Renamed folder").assertCountEquals(2)
        compose.onAllNodesWithText("Sample folder").assertCountEquals(0)
    }

    private fun setConfirmedTrashScreen(current: () -> FilesBrowserState, events: MutableList<FilesBrowserEvent>) {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvFilesScreen(
                    state = current(),
                    onEvent = { events += it; true },
                    onPlayMedia = {},
                    confirmedTrashEnabled = true,
                )
            }
        }
    }
}
