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
import io.putdotio.android.files.FilesBrowserTransition
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteStatus
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesRepositoryResult
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
class FilesDeleteRecoveryUiProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Synthetic delete recovery capture requires opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.delete.ui.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun uncertainDeleteKeepsItemAndOffersStatusCheck() {
        // Controlled reducer/UI evidence only: effects are recorded, never executed against an API.
        val preview = DeleteRecoveryPreview()
        compose.setContent {
            PutioTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MobileFilesScreen(
                        preview.state, { preview.dispatch(it) }, onPlayVideo = {}, confirmedTrashEnabled = true,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    )
                }
            }
        }
        compose.runOnIdle {
            preview.dispatch(FilesBrowserEvent.Delete(preview.folder.id, preview.item.id, FilesDeleteMode.TRASH))
            assertTrue(preview.effects.single() is FilesBrowserEffect.Delete)
        }
        assertNavigationRetainsRecovery(preview)
        compose.runOnIdle { preview.finishMutationWithUnknownResult() }
        assertNavigationRetainsRecovery(preview)
        compose.runOnIdle { preview.failStatusRead() }
        assertNavigationRetainsRecovery(preview)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.mobile_files_delete_unknown)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mobile_files_check_status)).assertIsDisplayed()
        compose.waitForIdle()
        deleteProofScreenshot("synthetic-recovery")
        compose.onNodeWithTag(MOBILE_FILES_OPERATION_RETRY_TAG).performClick()
        compose.runOnIdle {
            assertEquals(listOf(FilesBrowserEffect.Delete::class, FilesBrowserEffect.CheckDelete::class,
                FilesBrowserEffect.CheckDelete::class), preview.effects.map { it::class })
            val retry = preview.effects.last() as FilesBrowserEffect.CheckDelete
            assertEquals(preview.item.id, retry.itemId)
        }
        assertNavigationRetainsRecovery(preview)
        compose.runOnIdle { preview.finishStatusRead() }
        assertNavigationRetainsRecovery(preview)
        assertFailedReloadRetriesOnlyFolderRead(preview)
        compose.runOnIdle { preview.finishFolderReload() }
        assertNavigationResumes(preview)
    }

    private fun assertNavigationRetainsRecovery(preview: DeleteRecoveryPreview) {
        compose.runOnIdle {
            val retained = preview.state
            val external = preview.item.copy(id = FilesItemId(99), parentId = FilesItemId(0))
            for (event in listOf(FilesBrowserEvent.NavigateBack, FilesBrowserEvent.OpenExternalItem(external),
                FilesBrowserEvent.OpenExternalItem(external.copy(type = PutioFileType.VIDEO)))) {
                val rejected = preview.dispatch(event)
                assertFalse(rejected.consumed)
                assertSame(retained, rejected.state)
                assertNull(rejected.effect)
            }
            assertEquals(preview.folder, preview.state.current.folder)
        }
        compose.onNodeWithText(preview.item.name).assertIsDisplayed()
    }

    private fun assertFailedReloadRetriesOnlyFolderRead(preview: DeleteRecoveryPreview) {
        compose.runOnIdle {
            val read = preview.effects.last() as FilesBrowserEffect.LoadFolder
            preview.dispatch(FilesBrowserEvent.LoadFailed(read.requestId,
                FilesFailure.Unexpected(IllegalStateException("Synthetic folder reload failure"))))
        }
        assertNavigationRetainsRecovery(preview)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag(MOBILE_FILES_OPERATION_RETRY_TAG)
            .assertTextEquals(context.getString(R.string.mobile_action_retry)).performClick()
        compose.runOnIdle {
            val retry = preview.effects.last() as FilesBrowserEffect.LoadFolder
            assertEquals(preview.folder.id, retry.folderId)
        }
        assertNavigationRetainsRecovery(preview)
    }

    private fun assertNavigationResumes(preview: DeleteRecoveryPreview) {
        compose.runOnIdle {
            assertEquals(FilesDeleteStatus.STILL_PRESENT, preview.state.current.deleteOutcome?.status)
            assertEquals(FilesFolderOperation.Idle, preview.state.current.operation)
            assertEquals(1, preview.effects.count { it is FilesBrowserEffect.Delete })
            assertEquals(2, preview.effects.count { it is FilesBrowserEffect.CheckDelete })
            assertEquals(2, preview.effects.count { it is FilesBrowserEffect.LoadFolder })
        }
        compose.onNodeWithText(preview.item.name).performClick()
        compose.runOnIdle {
            assertEquals(preview.item.id, preview.state.current.folder.id)
            val read = preview.effects.last() as FilesBrowserEffect.LoadFolder
            assertEquals(preview.item.id, read.folderId)
            preview.dispatch(FilesBrowserEvent.LoadSucceeded(read.requestId, FilesPage(emptyList(), null)))
            assertTrue(preview.state.current.content is FilesContent.Empty)
            assertTrue(preview.dispatch(FilesBrowserEvent.NavigateBack).consumed)
            assertEquals(preview.folder, preview.state.current.folder)
            assertEquals(FilesDeleteStatus.STILL_PRESENT, preview.state.current.deleteOutcome?.status)
        }
        compose.onNodeWithText(preview.item.name).assertIsDisplayed()
    }
}

private class DeleteRecoveryPreview {
    val folder = FilesFolder(FilesItemId(12), "Delete recovery preview")
    val item = FilesItem(FilesItemId(13), folder.id, "A Action été", PutioFileType.FOLDER, 0, "2026-01-01T00:00:00Z")
    val effects = mutableListOf<FilesBrowserEffect>()
    var state by mutableStateOf(FilesBrowserState(
        stack = listOf(
            FilesFolderState(FilesFolder.Root, FilesContent.Ready(
                listOf(item.copy(id = folder.id, parentId = FilesFolder.Root.id, name = checkNotNull(folder.name))),
                FilesPaging.Complete,
            )),
            FilesFolderState(folder, FilesContent.Ready(listOf(item), FilesPaging.Complete)),
        ),
        nextRequestValue = 1,
    ))
        private set

    fun dispatch(event: FilesBrowserEvent): FilesBrowserTransition =
        FilesBrowserReducer.reduce(state, event).also { transition ->
            state = transition.state
            transition.effect?.let(effects::add)
        }

    fun finishMutationWithUnknownResult() {
        val deleting = effects.last() as FilesBrowserEffect.Delete
        dispatch(FilesBrowserEvent.DeleteFinished(deleting.requestId,
            FilesRepositoryResult.Failure(FilesFailure.Unexpected(IllegalStateException("Synthetic unknown result")))))
        assertTrue(effects.last() is FilesBrowserEffect.CheckDelete)
    }

    fun failStatusRead() {
        val checking = effects.last() as FilesBrowserEffect.CheckDelete
        dispatch(FilesBrowserEvent.DeleteChecked(checking.requestId,
            FilesRepositoryResult.Failure(FilesFailure.Unexpected(IllegalStateException("Synthetic offline read")))))
        assertEquals(FilesDeleteStatus.UNKNOWN, state.current.deleteOutcome?.status)
        assertTrue(state.current.operation is FilesFolderOperation.Failed)
    }

    fun finishStatusRead() {
        val checking = effects.last() as FilesBrowserEffect.CheckDelete
        dispatch(FilesBrowserEvent.DeleteChecked(checking.requestId, FilesRepositoryResult.Success(item)))
        assertEquals(folder.id, (effects.last() as FilesBrowserEffect.LoadFolder).folderId)
    }

    fun finishFolderReload() {
        val reloading = effects.last() as FilesBrowserEffect.LoadFolder
        dispatch(FilesBrowserEvent.LoadSucceeded(reloading.requestId, FilesPage(listOf(item), null)))
    }
}
