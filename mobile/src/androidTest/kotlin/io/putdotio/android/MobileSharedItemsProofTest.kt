package io.putdotio.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCopyId
import io.putdotio.android.files.FilesCopyProgress
import io.putdotio.android.files.FilesCopyStatus
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.MOBILE_FILES_COPY_ACTION_TAG
import io.putdotio.android.files.MOBILE_FILES_COPY_DISMISS_TAG
import io.putdotio.android.files.MOBILE_FILES_DOWNLOAD_ACTION_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_HERE_TAG
import io.putdotio.android.files.MOBILE_FILES_SHARE_ACTION_TAG
import io.putdotio.android.files.MobileFilesRoute
import io.putdotio.android.files.mobileFilesMoveFolderTag
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Synthetic proof of what shared-with-me rows offer, including Make a copy end to end through the
 * production Files route, controller and move picker over a faked repository; no API calls.
 */
@RunWith(AndroidJUnit4::class)
class MobileSharedItemsProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Synthetic shared-items proof requires opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.shared.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun sharedRowsOfferReadingActionsAndMakeACopyButNoOwnerActions() {
        val repository = SharedItemsRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = FilesBrowserController(repository, scope)
        try {
            compose.setContent {
                val state by controller.state.collectAsState()
                PutioTheme {
                    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        MobileFilesRoute(
                            state = state,
                            repository = repository,
                            onEvent = controller::dispatch,
                            onPlayMedia = {},
                            confirmedTrashEnabled = true,
                            onAuthenticationRequired = {},
                            onDownloadItem = {},
                            onShareItem = {},
                        )
                    }
                }
            }
            compose.waitUntil(5_000) { controller.state.value.current.content is FilesContent.Ready }

            for (folder in listOf(SHARED_ROOT, FRIEND)) {
                compose.onNodeWithText(folder).assertIsDisplayed()
                compose.onNodeWithContentDescription("Actions for $folder").assertDoesNotExist()
            }
            compose.onNodeWithContentDescription("Actions for $SHARED_FOLDER").assertIsDisplayed()
            screenshot("01-list")

            compose.onNodeWithContentDescription("Actions for $SHARED_VIDEO").performClick()
            compose.onNodeWithTag(MOBILE_FILES_DOWNLOAD_ACTION_TAG).assertIsDisplayed()
            compose.onNodeWithTag(MOBILE_FILES_SHARE_ACTION_TAG).assertIsDisplayed()
            compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).assertIsDisplayed()
            for (owner in listOf("Rename", "Move", "Move to trash")) {
                compose.onAllNodesWithText(owner).assertCountEquals(0)
            }
            screenshot("02-shared-file-actions")

            compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(mobileFilesMoveFolderTag(DESTINATION.id)).fetchSemanticsNodes().isNotEmpty()
            }
            screenshot("03-copy-picker")

            compose.onNodeWithTag(mobileFilesMoveFolderTag(DESTINATION.id)).performClick()
            compose.onNodeWithText("No folders here.").assertIsDisplayed()
            compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).performClick()
            compose.waitUntil(5_000) { repository.checking.isCompleted }
            compose.onNodeWithText("Copying “$SHARED_VIDEO” to ${DESTINATION.name}…").assertIsDisplayed()
            screenshot("04-copying")

            repository.finish.complete(FilesCopyProgress.Done)
            compose.waitUntil(5_000) { controller.state.value.copyOutcome?.status == FilesCopyStatus.COPIED }
            compose.onNodeWithText("Copied “$SHARED_VIDEO” to ${DESTINATION.name}.").assertIsDisplayed()
            check(repository.copies == listOf(FilesItemId(13L) to DESTINATION.id)) { "Copied ${repository.copies}" }
            screenshot("05-copied")

            compose.onNodeWithTag(MOBILE_FILES_COPY_DISMISS_TAG).performClick()
            compose.onNodeWithContentDescription("Actions for $OWNED_VIDEO").performClick()
            for (owner in listOf("Rename", "Move", "Move to trash")) compose.onNodeWithText(owner).assertIsDisplayed()
            compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).assertDoesNotExist()
            screenshot("06-owned-file-actions")
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    private fun screenshot(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = UUID.fromString(requireNotNull(InstrumentationRegistry.getArguments().getString("putio.shared.runId")))
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "shared-proof-$runId")
        check(directory.mkdirs() || directory.isDirectory)
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

private const val SHARED_ROOT = "Items shared with you"
private const val FRIEND = "friend"
private const val SHARED_FOLDER = "Archive été 東京"
private const val SHARED_VIDEO = "Harbor film.mp4"
private const val OWNED_VIDEO = "Sample clip.mp4"

private fun item(id: Long, name: String, type: PutioFileType) =
    FilesItem(FilesItemId(id), FilesFolder.Root.id, name, type, 128_000_000, "2026-09-08T12:00:00Z")

private val DESTINATION = item(20, "Sample folder", PutioFileType.FOLDER)

private class SharedItemsRepository : FilesRepository {
    val copies = mutableListOf<Pair<FilesItemId, FilesItemId>>()
    val checking = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<FilesCopyProgress>()

    override suspend fun loadFolder(folderId: FilesItemId) = FilesRepositoryResult.Success(FilesPage(listOf(
        item(10, SHARED_ROOT, PutioFileType.FOLDER).copy(folderType = PutioFolderType.SHARED_ROOT),
        item(11, FRIEND, PutioFileType.FOLDER).copy(folderType = PutioFolderType.SHARED_FRIEND),
        item(12, SHARED_FOLDER, PutioFileType.FOLDER).copy(isShared = true),
        item(13, SHARED_VIDEO, PutioFileType.VIDEO).copy(isShared = true),
        item(14, OWNED_VIDEO, PutioFileType.VIDEO),
        DESTINATION,
    ), null))

    override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) =
        FilesRepositoryResult.Success(
            FilesPage(if (folderId == FilesFolder.Root.id) listOf(DESTINATION) else emptyList(), null),
        )

    override suspend fun startCopy(
        itemId: FilesItemId,
        destinationId: FilesItemId,
    ): FilesRepositoryResult<FilesCopyId> {
        copies += itemId to destinationId
        return FilesRepositoryResult.Success(FilesCopyId(42L))
    }

    override suspend fun checkCopy(copyId: FilesCopyId): FilesRepositoryResult<FilesCopyProgress> {
        checking.complete(Unit)
        return FilesRepositoryResult.Success(finish.await())
    }

    override suspend fun loadNextPage(cursor: FilesCursor) = error("No paging")
    override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort) = error("No sort")
    override suspend fun rename(itemId: FilesItemId, name: String) = error("No rename")
    override suspend fun delete(itemId: FilesItemId, mode: FilesDeleteMode) = error("No delete")
    override suspend fun move(itemId: FilesItemId, destinationId: FilesItemId) = error("No move")
    override suspend fun resolveItem(itemId: FilesItemId) = error("No item resolution")
}
