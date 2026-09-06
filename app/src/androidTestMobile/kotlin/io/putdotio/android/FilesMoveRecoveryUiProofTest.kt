package io.putdotio.android

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesMoveDestinationEvent
import io.putdotio.android.files.FilesMoveDestinationFolder
import io.putdotio.android.files.FilesMoveDestinationRequest
import io.putdotio.android.files.FilesMoveDestinationState
import io.putdotio.android.files.FilesMoveStatus
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.complete
import io.putdotio.android.files.reduce
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class FilesMoveRecoveryUiProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Synthetic Move capture requires opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.move.ui.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun uncertainMoveRetainsSourceAndRetriesOnlyReads() {
        // Both surfaces use controlled reducers. No effect is executed against a repository or API.
        val preview = MoveRecoveryPreview()
        compose.setContent {
            PutioTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MobileFilesScreen(preview.state, preview::dispatch, {},
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
                    if (preview.showPicker) MobileFilesMoveDestination(preview.picker, preview::pickerEvent,
                        onCancel = { preview.showPicker = false },
                        onConfirm = { error("Unexpected picker submission") })
                }
            }
        }
        compose.runOnIdle { preview.startUnknownMove() }
        assertNavigationRetainsSource(preview)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.mobile_files_move_unknown)).assertIsDisplayed()
        moveProofScreenshot("synthetic-recovery")
        compose.onNodeWithTag(MOBILE_FILES_OPERATION_RETRY_TAG).performClick()
        compose.runOnIdle {
            assertEquals(1, preview.effects.count { it is FilesBrowserEffect.Move })
            assertEquals(2, preview.effects.count { it is FilesBrowserEffect.CheckMove })
            assertEquals(preview.item.id, (preview.effects.last() as FilesBrowserEffect.CheckMove).itemId)
            preview.confirmLocationAndFailReload()
        }
        assertNavigationRetainsSource(preview)
        compose.onNodeWithTag(MOBILE_FILES_OPERATION_RETRY_TAG)
            .assertTextEquals(context.getString(R.string.mobile_action_retry)).performClick()
        compose.runOnIdle { preview.finishSourceAndRefreshAncestor() }
        compose.onNodeWithText(preview.item.name).assertIsDisplayed()
        assertPickerReadRecovery(preview)
    }

    private fun assertNavigationRetainsSource(preview: MoveRecoveryPreview) {
        compose.runOnIdle {
            val retained = preview.state
            for (event in listOf(FilesBrowserEvent.NavigateBack,
                FilesBrowserEvent.OpenExternalItem(preview.item.copy(id = FilesItemId(99))))) {
                val transition = FilesBrowserReducer.reduce(retained, event)
                assertFalse(transition.consumed)
                assertSame(retained, transition.state)
                assertNull(transition.effect)
            }
        }
        compose.onNodeWithText(preview.item.name).assertIsDisplayed()
    }

    private fun assertPickerReadRecovery(preview: MoveRecoveryPreview) {
        val sourceState = preview.state
        compose.runOnIdle { preview.showPicker = true }
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_RETRY_TAG).assertIsDisplayed()
        moveProofScreenshot("synthetic-picker-error")
        compose.onNodeWithTag(MOBILE_FILES_MOVE_RETRY_TAG).performClick()
        compose.runOnIdle {
            val request = checkNotNull(preview.pickerRequest)
            assertEquals(FilesFolder.Root.id, request.folderId)
            assertNull(request.cursor)
            preview.picker = preview.picker.complete(request, FilesRepositoryResult.Success(
                FilesPage(listOf(preview.source), FilesCursor("synthetic-page-two")),
            ))
        }
        compose.onNodeWithTag(MOBILE_FILES_MOVE_LOAD_MORE_TAG).performClick()
        val destination = preview.source.copy(id = FilesItemId(18), name = "Empty destination 東京")
        compose.runOnIdle {
            val request = checkNotNull(preview.pickerRequest)
            assertEquals(FilesCursor("synthetic-page-two"), request.cursor)
            preview.picker = preview.picker.complete(request,
                FilesRepositoryResult.Success(FilesPage(listOf(destination), null)))
        }
        assertCurrentParentAndSelfAreDisabled(preview)
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).performClick()
        compose.runOnIdle {
            val request = checkNotNull(preview.pickerRequest)
            assertEquals(destination.id, request.folderId)
            preview.picker = preview.picker.complete(request,
                FilesRepositoryResult.Success(FilesPage(emptyList(), null)))
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.mobile_files_move_empty)).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsEnabled()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
        compose.runOnIdle {
            assertSame(sourceState, preview.state)
            assertFalse(preview.showPicker)
            assertEquals(1, preview.effects.count { it is FilesBrowserEffect.Move })
        }
    }

    private fun assertCurrentParentAndSelfAreDisabled(preview: MoveRecoveryPreview) {
        compose.onNodeWithTag(mobileFilesMoveFolderTag(preview.source.id)).performClick()
        compose.runOnIdle {
            val request = checkNotNull(preview.pickerRequest)
            assertEquals(preview.folder.id, request.folderId)
            preview.picker = preview.picker.complete(request,
                FilesRepositoryResult.Success(FilesPage(listOf(preview.item), null)))
        }
        compose.onNodeWithTag(mobileFilesMoveFolderTag(preview.item.id)).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_BACK_TAG).performClick()
        compose.runOnIdle { assertEquals(FilesFolder.Root.id, preview.picker.current.folder.id) }
    }
}

private class MoveRecoveryPreview {
    val folder = FilesFolder(FilesItemId(12), "Move recovery preview")
    val item = FilesItem(FilesItemId(13), folder.id, "A Folder été", PutioFileType.FOLDER, 0, "2026-01-01T00:00:00Z")
    val source = item.copy(id = folder.id, parentId = FilesFolder.Root.id, name = checkNotNull(folder.name))
    val effects = mutableListOf<FilesBrowserEffect>()
    var state by mutableStateOf(FilesBrowserState(listOf(
        FilesFolderState(FilesFolder.Root, FilesContent.Ready(listOf(source), FilesPaging.Complete)),
        FilesFolderState(folder, FilesContent.Ready(listOf(item), FilesPaging.Complete)),
    ), nextRequestValue = 1))
        private set
    var showPicker by mutableStateOf(false)
    var picker by mutableStateOf(FilesMoveDestinationState(item, folder.id,
        listOf(FilesMoveDestinationFolder(FilesFolder.Root, FilesContent.Failed(offline()))), nextRequestValue = 1))
    var pickerRequest: FilesMoveDestinationRequest? = null
        private set

    fun dispatch(event: FilesBrowserEvent) {
        val transition = FilesBrowserReducer.reduce(state, event)
        state = transition.state
        transition.effect?.let(effects::add)
    }
    fun pickerEvent(event: FilesMoveDestinationEvent) {
        val transition = picker.reduce(event)
        picker = transition.state
        pickerRequest = transition.request
    }
    fun startUnknownMove() {
        dispatch(FilesBrowserEvent.Move(folder.id, item.id, FilesFolder.Root.id))
        dispatch(FilesBrowserEvent.MoveFinished((effects.last() as FilesBrowserEffect.Move).requestId,
            FilesRepositoryResult.Failure(offline())))
        dispatch(FilesBrowserEvent.MoveChecked((effects.last() as FilesBrowserEffect.CheckMove).requestId,
            FilesRepositoryResult.Failure(offline())))
        assertEquals(FilesMoveStatus.UNKNOWN, state.current.moveOutcome?.status)
    }
    fun confirmLocationAndFailReload() {
        dispatch(FilesBrowserEvent.MoveChecked((effects.last() as FilesBrowserEffect.CheckMove).requestId,
            FilesRepositoryResult.Success(item.copy(parentId = FilesFolder.Root.id))))
        val read = effects.last() as FilesBrowserEffect.LoadFolder
        assertEquals(folder.id, read.folderId)
        dispatch(FilesBrowserEvent.LoadFailed(read.requestId, offline()))
    }
    fun finishSourceAndRefreshAncestor() {
        val read = effects.last() as FilesBrowserEffect.LoadFolder
        assertEquals(folder.id, read.folderId)
        dispatch(FilesBrowserEvent.LoadSucceeded(read.requestId, FilesPage(emptyList(), null)))
        assertEquals(FilesMoveStatus.MOVED, state.current.moveOutcome?.status)
        assertEquals(FilesFolderOperation.Idle, state.current.operation)
        dispatch(FilesBrowserEvent.NavigateBack)
        val ancestor = effects.last() as FilesBrowserEffect.LoadFolder
        assertEquals(FilesFolder.Root.id, ancestor.folderId)
        dispatch(FilesBrowserEvent.LoadSucceeded(ancestor.requestId,
            FilesPage(listOf(source, item.copy(parentId = FilesFolder.Root.id)), null)))
        assertEquals(1, effects.count { it is FilesBrowserEffect.Move })
        assertEquals(2, effects.count { it is FilesBrowserEffect.CheckMove })
        assertEquals(3, effects.count { it is FilesBrowserEffect.LoadFolder })
    }
    private fun offline() = FilesFailure.Unexpected(IllegalStateException("Synthetic offline request"))
}
