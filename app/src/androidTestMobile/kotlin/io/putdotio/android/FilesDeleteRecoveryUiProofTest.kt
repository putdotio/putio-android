package io.putdotio.android

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.FilesDeleteStatus
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
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
        // Controlled UI evidence only: this activity does not initialize auth or call the API.
        val item = FilesItem(FilesItemId(13), FilesItemId(12), "A Action été", PutioFileType.FOLDER, 0, "2026-01-01T00:00:00Z")
        val intent = FilesFolderOperationIntent.Delete(item.id, FilesDeleteMode.TRASH)
        val failure = FilesFailure.Unexpected(IllegalStateException("Synthetic unknown result"))
        val state = FilesBrowserState(
            stack = listOf(FilesFolderState(
                folder = FilesFolder(FilesItemId(12), "Delete recovery preview"),
                content = FilesContent.Ready(listOf(item), FilesPaging.Complete),
                deleteOutcome = FilesDeleteOutcome(
                    requestId = FilesRequestId(1), intent = intent, itemName = item.name,
                    status = FilesDeleteStatus.UNKNOWN, failure = failure,
                ),
                operation = FilesFolderOperation.Failed(
                    failure = failure,
                    intent = intent,
                    phase = FilesFolderOperationPhase.CHECKING_DELETE,
                ),
            )),
            nextRequestValue = 2,
        )
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MobileFilesScreen(
                        state, events::add, onPlayVideo = {}, confirmedTrashEnabled = true,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    )
                }
            }
        }
        compose.onNodeWithText(item.name).assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.mobile_files_delete_unknown)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mobile_files_check_status)).assertIsDisplayed()
        compose.waitForIdle()
        deleteProofScreenshot("synthetic-recovery")
        compose.onNodeWithTag(MOBILE_FILES_OPERATION_RETRY_TAG).performClick()
        assertEquals(listOf(FilesBrowserEvent.Retry), events)
    }
}
