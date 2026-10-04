package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.FilesDeleteStatus
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.MobileFilesScreen
import io.putdotio.android.files.copyForTest
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileFilesDeleteTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun moveToTrashRunsAtOnceWithoutAConfirmation() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme { MobileFilesScreen(loadedRoot(), events::add, {}, confirmedTrashEnabled = true) }
        }
        openAction("Move to trash")
        compose.onNodeWithText("Confirm").assertDoesNotExist()
        compose.onNodeWithText("Move to trash").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(
                listOf(FilesBrowserEvent.Delete(FilesFolder.Root.id, item.id, FilesDeleteMode.TRASH)),
                events.filterIsInstance<FilesBrowserEvent.Delete>(),
            )
        }
    }

    @Test
    fun aTrashTapQueuedBeforeTrashTurnedOffReachedTheSheetSendsNothing() {
        val events = mutableListOf<FilesBrowserEvent>()
        var trash by mutableStateOf<Boolean?>(true)
        compose.setContent {
            PutioTheme { MobileFilesScreen(loadedRoot(), events::add, {}, confirmedTrashEnabled = trash) }
        }
        compose.onNodeWithContentDescription("Actions for été 東京.mkv").performClick()
        val moveToTrash = checkNotNull(compose.onNodeWithText("Move to trash")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle {
            trash = false
            moveToTrash()
        }
        compose.runOnIdle {
            assertEquals(emptyList<FilesBrowserEvent>(), events.filterIsInstance<FilesBrowserEvent.Delete>())
        }
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun permanentConfirmationIsExplicitAndQueuedClicksSubmitOnlyOnce() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme { MobileFilesScreen(loadedRoot(), events::add, {}, confirmedTrashEnabled = false) }
        }
        openAction("Delete")
        compose.onNodeWithText("Permanently delete “été 東京.mkv”? This cannot be undone.").assertIsDisplayed()
        val confirm = checkNotNull(compose.onNodeWithText("Confirm")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle {
            confirm()
            confirm()
            assertEquals(
                listOf(FilesBrowserEvent.Delete(FilesFolder.Root.id, item.id, FilesDeleteMode.PERMANENT)),
                events.filterIsInstance<FilesBrowserEvent.Delete>(),
            )
        }
        openAction("Delete")
        compose.onNodeWithText("Confirm").performClick()
        compose.runOnIdle { assertEquals(2, events.filterIsInstance<FilesBrowserEvent.Delete>().size) }
    }

    @Test
    fun deleteCancelAndConfirmHandleAnEarlierRenameFailure() {
        val rename = FilesFolderOperationIntent.Rename(item.id, "failed name")
        val root = loadedRoot()
        val failedRename = root.copyForTest(stack = listOf(root.current.copy(
            operation = FilesFolderOperation.Failed(
                FilesFailure.Unexpected(IllegalStateException("rename failed")),
                rename, FilesFolderOperationPhase.RENAMING,
            ),
        )))
        var state by mutableStateOf(failedRename)
        val effects = mutableListOf<FilesBrowserEffect>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state, { event ->
                    val next = FilesBrowserReducer.reduce(state, event)
                    state = next.state
                    next.effect?.let(effects::add)
                }, {}, confirmedTrashEnabled = false)
            }
        }
        openAction("Delete")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(FilesFolderOperation.Idle, state.current.operation)
            assertTrue(effects.isEmpty())
            state = failedRename
        }
        openAction("Delete")
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Confirm").assertDoesNotExist()
        compose.runOnIdle {
            val deletion = effects.single() as FilesBrowserEffect.Delete
            assertEquals(item.id, deletion.itemId)
            assertEquals(FilesDeleteMode.PERMANENT, deletion.mode)
            assertEquals(
                FilesFolderOperation.Loading(
                    deletion.requestId, FilesFolderOperationIntent.Delete(item.id, FilesDeleteMode.PERMANENT),
                    FilesFolderOperationPhase.DELETING,
                ),
                state.current.operation,
            )
        }
    }

    @Test
    fun unknownTrashSettingsLeaveRenameAvailableButBlockDelete() {
        compose.setContent {
            PutioTheme { MobileFilesScreen(loadedRoot(), {}, {}, confirmedTrashEnabled = null) }
        }
        compose.onNodeWithContentDescription("Actions for été 東京.mkv").performClick()
        compose.onNodeWithText("Waiting for confirmed Trash settings.").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun trashTurningOnWithdrawsThePermanentConfirmation() {
        var trash by mutableStateOf<Boolean?>(false)
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme { MobileFilesScreen(loadedRoot(), events::add, {}, confirmedTrashEnabled = trash) }
        }
        openAction("Delete")
        compose.onNodeWithText("Permanently delete “été 東京.mkv”? This cannot be undone.").assertIsDisplayed()
        compose.runOnIdle { trash = true }
        compose.onNodeWithText("Confirm").assertDoesNotExist()
        compose.onNodeWithText("Move to trash").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(FilesDeleteMode.TRASH),
                events.filterIsInstance<FilesBrowserEvent.Delete>().map { it.mode },
            )
        }
    }

    @Test
    fun unconfirmedSettingsDiscardTheOldConfirmationEvenWhenModeReturns() {
        var trash by mutableStateOf<Boolean?>(false)
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme { MobileFilesScreen(loadedRoot(), events::add, {}, confirmedTrashEnabled = trash) }
        }
        openAction("Delete")
        compose.runOnIdle { trash = null }
        compose.onNodeWithText("Confirm").assertDoesNotExist()
        compose.runOnIdle { trash = false }
        compose.onNodeWithText("Confirm").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(events.none { it is FilesBrowserEvent.Delete })
        }
    }

    @Test
    fun uncertainDeleteOffersReadOnlyStatusRecovery() {
        var state by mutableStateOf(loadedRoot())
        val effects = mutableListOf<FilesBrowserEffect>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state, { event ->
                    val next = FilesBrowserReducer.reduce(state, event)
                    state = next.state
                    next.effect?.let(effects::add)
                }, {}, confirmedTrashEnabled = true)
            }
        }
        openAction("Move to trash")
        compose.runOnIdle {
            val check = FilesBrowserReducer.reduce(state, FilesBrowserEvent.LoadFailed(
                effects.single().requestId, FilesFailure.Unexpected(IllegalStateException("lost response")),
            ))
            effects += checkNotNull(check.effect)
            state = FilesBrowserReducer.reduce(check.state, FilesBrowserEvent.LoadFailed(
                checkNotNull(check.effect).requestId, FilesFailure.Unexpected(IllegalStateException("read failed")),
            )).state
        }
        compose.onNodeWithText("Check status").performClick()
        compose.runOnIdle {
            assertEquals(1, effects.filterIsInstance<FilesBrowserEffect.Delete>().size)
            assertTrue(effects.last() !is FilesBrowserEffect.Delete)
            val reload = FilesBrowserReducer.reduce(state, FilesBrowserEvent.DeleteChecked(
                effects.last().requestId, FilesRepositoryResult.Success(item),
            ))
            effects += checkNotNull(reload.effect)
            state = FilesBrowserReducer.reduce(reload.state, FilesBrowserEvent.LoadFailed(
                checkNotNull(reload.effect).requestId, FilesFailure.Unexpected(IllegalStateException("reload failed")),
            )).state
        }
        compose.onNodeWithText("Check status").assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle {
            assertTrue(effects.last() is FilesBrowserEffect.LoadFolder)
            assertEquals(1, effects.filterIsInstance<FilesBrowserEffect.Delete>().size)
        }
    }

    @Test
    fun aConfirmedTrashMoveIsAnnouncedOnceWithViewTrashAndNoUndo() {
        val remaining = item.copy(id = FilesItemId(8L), name = "Sample folder", type = PutioFileType.FOLDER)
        val intent = FilesFolderOperationIntent.Delete(FilesItemId(7L), FilesDeleteMode.TRASH)
        val outcome = FilesDeleteOutcome(
            FilesRequestId(4L), intent, "Harbor film.mp4", status = FilesDeleteStatus.NO_LONGER_AVAILABLE,
        )
        var state by mutableStateOf(
            loadedRoot(listOf(remaining)).let {
                it.copyForTest(stack = listOf(it.current.copy(deleteOutcome = outcome)))
            },
        )
        val events = mutableListOf<FilesBrowserEvent>()
        var viewedTrash = 0
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(
                    state = state, onEvent = { events += it }, onPlayMedia = {}, onViewTrash = { viewedTrash++ },
                )
            }
        }

        compose.onNodeWithText("Moved to Trash").assertIsDisplayed()
        compose.onAllNodesWithText("Undo").assertCountEquals(0)
        compose.onAllNodesWithText("“Harbor film.mp4” is no longer available in Files.").assertCountEquals(0)
        compose.onNodeWithText("View Trash").performClick()
        compose.runOnIdle {
            assertEquals(1, viewedTrash)
            assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.DeleteOutcomeAnnounced(outcome)), events)
        }
        compose.onAllNodesWithText("Moved to Trash").assertCountEquals(0)

        // Once announced, the kept outcome is neither announced again nor shown as a line.
        compose.runOnIdle {
            state = state.copyForTest(
                stack = listOf(state.current.copy(deleteOutcome = outcome.copy(announced = true))),
            )
        }
        compose.onAllNodesWithText("Moved to Trash").assertCountEquals(0)
        compose.onAllNodesWithText("“Harbor film.mp4” is no longer available in Files.").assertCountEquals(0)

        // Permanent deletion keeps its line in the folder and is not announced as a Trash move.
        compose.runOnIdle {
            state = state.copyForTest(stack = listOf(state.current.copy(deleteOutcome = outcome.copy(
                requestId = FilesRequestId(5L), intent = intent.copy(mode = FilesDeleteMode.PERMANENT),
            ))))
        }
        compose.onNodeWithText("“Harbor film.mp4” is no longer available in Files.").assertIsDisplayed()
        compose.onAllNodesWithText("Moved to Trash").assertCountEquals(0)

        // A failed Trash request whose item is gone anyway proves no Trash move either.
        compose.runOnIdle {
            state = state.copyForTest(stack = listOf(state.current.copy(deleteOutcome = outcome.copy(
                requestId = FilesRequestId(6L), failure = FilesFailure.Unexpected(IllegalStateException("rejected")),
            ))))
        }
        compose.onNodeWithText("“Harbor film.mp4” is no longer available in Files.").assertIsDisplayed()
        compose.onAllNodesWithText("Moved to Trash").assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, events.size) }
    }

    @Test
    fun aFolderTooLargeForTrashExplainsAndDeletesPermanentlyOnlyAfterConfirmation() {
        val folder = item.copy(id = FilesItemId(8L), name = "Sample folder", type = PutioFileType.FOLDER)
        val outcome = FilesDeleteOutcome(
            FilesRequestId(4L), FilesFolderOperationIntent.Delete(folder.id, FilesDeleteMode.TRASH), folder.name,
            status = FilesDeleteStatus.TOO_LARGE_FOR_TRASH,
        )
        fun withOutcome(items: List<FilesItem>) =
            loadedRoot(items).let { it.copyForTest(stack = listOf(it.current.copy(deleteOutcome = outcome))) }
        var state by mutableStateOf(withOutcome(listOf(folder)))
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme { MobileFilesScreen(state, events::add, {}, confirmedTrashEnabled = true) }
        }
        val message = "This folder contains too many files. Would you want to delete it PERMANENTLY?"

        compose.onNodeWithText("We couldn’t send these files to trash").assertIsDisplayed()
        compose.onAllNodesWithText("“Sample folder” is still in Files. Open its actions to try again.")
            .assertCountEquals(0)
        compose.onNodeWithText("Delete permanently").performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onAllNodesWithText("Sample folder").assertCountEquals(2)
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodesWithText(message).assertCountEquals(0)
        compose.runOnIdle { assertEquals(emptyList<FilesBrowserEvent>(), events) }

        compose.onNodeWithText("Delete permanently").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(FilesBrowserEvent.Delete(FilesFolder.Root.id, folder.id, FilesDeleteMode.PERMANENT)),
                events,
            )
        }
        compose.onAllNodesWithText(message).assertCountEquals(0)

        // A rename keeps the outcome; the confirmation names the folder as it is listed now.
        compose.runOnIdle { state = withOutcome(listOf(folder.copy(name = "Renamed folder"))) }
        compose.onNodeWithText("Delete permanently").performClick()
        compose.onAllNodesWithText("Renamed folder").assertCountEquals(2)
        compose.onAllNodesWithText("Sample folder").assertCountEquals(0)
        compose.onNodeWithText("Cancel").performClick()

        // A refresh that no longer lists the folder keeps the explanation but offers nothing to delete.
        compose.runOnIdle { state = withOutcome(emptyList()) }
        compose.onNodeWithText("We couldn’t send these files to trash").assertIsDisplayed()
        compose.onAllNodesWithText("Delete permanently").assertCountEquals(0)
    }

    private fun openAction(label: String) {
        compose.onNodeWithContentDescription("Actions for été 東京.mkv").performClick()
        compose.onNodeWithText(label).performClick()
    }

    private fun loadedRoot(items: List<FilesItem> = listOf(item)): FilesBrowserState {
        val initial = FilesBrowserReducer.start()
        return FilesBrowserReducer.reduce(initial.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(initial.effect).requestId, FilesPage(items, null),
        )).state
    }

    private val item = FilesItem(
        id = FilesItemId(7L), parentId = FilesFolder.Root.id, name = "été 東京.mkv",
        type = PutioFileType.VIDEO, sizeBytes = 128L, createdAt = "2026-09-05T00:00:00Z",
    )
}
