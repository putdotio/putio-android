package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
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
    fun trashConfirmationNamesTheItemAndCancelSendsNothing() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme { MobileFilesScreen(loadedRoot(), events::add, {}, confirmedTrashEnabled = true) }
        }
        openAction("Move to trash")
        compose.onNodeWithText("Move “été 東京.mkv” to trash? You can restore it from Trash.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(events.none { it is FilesBrowserEvent.Delete }) }
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
    fun changedTrashModeRequiresAFreshConfirmation() {
        var trash by mutableStateOf<Boolean?>(true)
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme { MobileFilesScreen(loadedRoot(), events::add, {}, confirmedTrashEnabled = trash) }
        }
        openAction("Move to trash")
        compose.runOnIdle { trash = false }
        compose.onNodeWithText("Confirm").assertDoesNotExist()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Permanently delete “été 東京.mkv”? This cannot be undone.").assertIsDisplayed()
        compose.onNodeWithText("Confirm").performClick()
        compose.runOnIdle {
            assertEquals(listOf(FilesDeleteMode.PERMANENT), events.filterIsInstance<FilesBrowserEvent.Delete>().map { it.mode })
        }
    }

    @Test
    fun unconfirmedSettingsDiscardTheOldConfirmationEvenWhenModeReturns() {
        var trash by mutableStateOf<Boolean?>(true)
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme { MobileFilesScreen(loadedRoot(), events::add, {}, confirmedTrashEnabled = trash) }
        }
        openAction("Move to trash")
        compose.runOnIdle { trash = null }
        compose.onNodeWithText("Confirm").assertDoesNotExist()
        compose.runOnIdle { trash = true }
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
        compose.onNodeWithText("Confirm").performClick()
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
        }
    }

    private fun openAction(label: String) {
        compose.onNodeWithContentDescription("Actions for été 東京.mkv").performClick()
        compose.onNodeWithText(label).performClick()
    }

    private fun loadedRoot(): FilesBrowserState {
        val initial = FilesBrowserReducer.start()
        return FilesBrowserReducer.reduce(initial.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(initial.effect).requestId, FilesPage(listOf(item), null),
        )).state
    }

    private val item = FilesItem(
        id = FilesItemId(7L), parentId = FilesFolder.Root.id, name = "été 東京.mkv",
        type = PutioFileType.VIDEO, sizeBytes = 128L, createdAt = "2026-09-05T00:00:00Z",
    )
}
