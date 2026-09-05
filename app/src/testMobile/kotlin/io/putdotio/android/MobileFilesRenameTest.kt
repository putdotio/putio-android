package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileFilesRenameTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aQueuedRefreshCannotDismissARejectedRenameDraft() {
        var state by mutableStateOf(loadedRoot())
        var renameAccepted = true
        val effects = mutableListOf<FilesBrowserEffect>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state, onEvent = { event ->
                    val transition = FilesBrowserReducer.reduce(state, event)
                    state = transition.state
                    if (event is FilesBrowserEvent.Rename) renameAccepted = transition.consumed
                    transition.effect?.let(effects::add)
                }, onPlayVideo = {})
            }
        }
        compose.onNodeWithContentDescription("Actions for old.mkv").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG).performTextReplacement("unsaved.mkv")
        val queuedSave = checkNotNull(
            compose.onNodeWithText("Save").fetchSemanticsNode().config[SemanticsActions.OnClick].action,
        )
        compose.runOnIdle {
            // Dispatch a queued refresh before the Save callback from the last composition.
            val refresh = FilesBrowserReducer.reduce(state, FilesBrowserEvent.Refresh)
            state = refresh.state
            queuedSave()
            state = FilesBrowserReducer.reduce(state, FilesBrowserEvent.LoadSucceeded(
                checkNotNull(refresh.effect).requestId, FilesPage(listOf(file), null),
            )).state
            assertFalse(renameAccepted)
            assertEquals(emptyList<FilesBrowserEffect>(), effects)
        }
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG)
            .assertIsDisplayed()
            .assertTextContains("unsaved.mkv")
    }

    @Test
    fun cancellingAFailedRenameRemovesItsRetry() {
        var state by mutableStateOf(failedRename())
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state, onEvent = { state = FilesBrowserReducer.reduce(state, it).state }, onPlayVideo = {})
            }
        }
        compose.onNodeWithContentDescription("Actions for old.mkv").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG).assertTextContains("abandoned.mkv")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(FilesFolderOperation.Idle, state.current.operation)
            assertNull(FilesBrowserReducer.reduce(state, FilesBrowserEvent.Retry).effect)
        }
    }

    @Test
    fun savingTheOriginalNameAfterFailureRemovesTheAbandonedRetry() {
        var state by mutableStateOf(failedRename())
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state, onEvent = { state = FilesBrowserReducer.reduce(state, it).state }, onPlayVideo = {})
            }
        }
        compose.onNodeWithContentDescription("Actions for old.mkv").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG).performTextReplacement("old.mkv")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(FilesFolderOperation.Idle, state.current.operation)
            assertNull(FilesBrowserReducer.reduce(state, FilesBrowserEvent.Retry).effect)
        }
    }

    private fun loadedRoot(): FilesBrowserState {
        val start = FilesBrowserReducer.start()
        return FilesBrowserReducer.reduce(start.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(start.effect).requestId, FilesPage(listOf(file), null),
        )).state
    }

    private fun failedRename(): FilesBrowserState {
        val rename = FilesBrowserReducer.reduce(
            loadedRoot(), FilesBrowserEvent.Rename(FilesFolder.Root.id, file.id, "abandoned.mkv"),
        )
        return FilesBrowserReducer.reduce(rename.state, FilesBrowserEvent.LoadFailed(
            checkNotNull(rename.effect).requestId, FilesFailure.Unexpected(IllegalStateException("rejected")),
        )).state
    }

    private val file = FilesItem(
        id = FilesItemId(7L),
        parentId = FilesFolder.Root.id,
        name = "old.mkv",
        type = PutioFileType.VIDEO,
        sizeBytes = 128L,
        createdAt = "2026-09-05T00:00:00Z",
    )
}
