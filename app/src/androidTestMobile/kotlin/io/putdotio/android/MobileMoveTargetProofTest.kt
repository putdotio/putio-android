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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesMoveTargetMemory
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.MOBILE_FILES_COPY_ACTION_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_BACK_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_CANCEL_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_FOLDER_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_HERE_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_REMEMBER_TAG
import io.putdotio.android.files.MobileFilesRoute
import io.putdotio.android.files.MobileMoveTargetStore
import io.putdotio.android.files.mobileFilesMoveFolderTag
import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
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
 * Synthetic proof of "Remember target folder" through the production Files route, controller,
 * move picker and on-device store over a faked repository; no API calls.
 */
@RunWith(AndroidJUnit4::class)
class MobileMoveTargetProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Synthetic move-target proof requires opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.movetarget.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun rememberedFolderReopensMoveAndCopyUntilSignOutAndAMissingOneOpensAtRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MobileMoveTargetStore.clearAll(context)
        val store = MobileMoveTargetStore(context, USER_ID)
        val repository = MoveTargetRepository()
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
                            moveTargetStore = store,
                        )
                    }
                }
            }
            compose.waitUntil(5_000) { controller.state.value.current.content is FilesContent.Ready }

            openPicker(CLIP.name, "Move")
            awaitFolderRow(SAMPLE_FOLDER)
            compose.onNodeWithTag(MOBILE_FILES_MOVE_REMEMBER_TAG).assertIsOff()
            screenshot("01-move-default-root")

            compose.onNodeWithTag(MOBILE_FILES_MOVE_REMEMBER_TAG).performClick().assertIsOn()
            compose.onNodeWithTag(mobileFilesMoveFolderTag(SAMPLE_FOLDER.id)).performClick()
            awaitFolderRow(ARCHIVE)
            compose.onNodeWithTag(mobileFilesMoveFolderTag(ARCHIVE.id)).performClick()
            compose.onNodeWithText("No folders here.").assertIsDisplayed()
            screenshot("02-remember-on-chosen-folder")
            compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).performClick()
            compose.waitUntil(5_000) {
                (controller.state.value.current.content as? FilesContent.Ready)?.items?.none { it.id == CLIP.id } == true
            }
            check(repository.moves == listOf(CLIP.id to ARCHIVE.id)) { "Moved ${repository.moves}" }
            val remembered = FilesMoveTargetMemory(true, listOf(SAMPLE_FOLDER, ARCHIVE).map { FilesFolder(it.id, it.name) })
            check(MobileMoveTargetStore(context, USER_ID).read() == remembered) { "Stored ${store.read()}" }
            check(MobileMoveTargetStore(context, OTHER_USER_ID).read() == FilesMoveTargetMemory()) { "Leaked" }

            openPicker(NOTES.name, "Move")
            awaitEmptyFolder()
            compose.onNodeWithTag(MOBILE_FILES_MOVE_FOLDER_TAG).assertTextEquals(ARCHIVE.name)
            compose.onNodeWithTag(MOBILE_FILES_MOVE_REMEMBER_TAG).assertIsOn()
            screenshot("03-move-reopens-at-remembered")
            compose.onNodeWithTag(MOBILE_FILES_MOVE_BACK_TAG).performClick()
            awaitFolderRow(ARCHIVE)
            compose.onNodeWithTag(MOBILE_FILES_MOVE_FOLDER_TAG).assertTextEquals(SAMPLE_FOLDER.name)
            screenshot("04-back-reads-parent")
            compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()

            openPicker(SHARED_VIDEO.name, null)
            awaitEmptyFolder()
            compose.onNodeWithTag(MOBILE_FILES_MOVE_FOLDER_TAG).assertTextEquals(ARCHIVE.name)
            compose.onNodeWithText("Make a copy").assertIsDisplayed()
            screenshot("05-copy-reopens-at-remembered")
            compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()

            // The auth runtime runs this on sign-out and on an expired session.
            MobileMoveTargetStore.clearAll(context)
            openPicker(NOTES.name, "Move")
            awaitFolderRow(SAMPLE_FOLDER)
            compose.onNodeWithTag(MOBILE_FILES_MOVE_REMEMBER_TAG).assertIsOff()
            screenshot("06-after-sign-out-root")
            compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()

            store.write(FilesMoveTargetMemory(true, listOf(FilesFolder(REMOVED_ID, "Removed folder"))))
            openPicker(NOTES.name, "Move")
            awaitFolderRow(SAMPLE_FOLDER)
            compose.onNodeWithTag(MOBILE_FILES_MOVE_REMEMBER_TAG).assertIsOn()
            check(REMOVED_ID in repository.listed) { "Never read the removed folder" }
            screenshot("07-missing-folder-root")
            compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
        } finally {
            controller.close()
            scope.cancel()
            MobileMoveTargetStore.clearAll(context)
        }
    }

    private fun openPicker(itemName: String, action: String?) {
        compose.onNodeWithContentDescription("Actions for $itemName").performClick()
        if (action == null) {
            compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).performClick()
        } else {
            compose.onNodeWithText(action).performClick()
        }
    }

    private fun awaitFolderRow(folder: FilesItem) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(mobileFilesMoveFolderTag(folder.id)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitEmptyFolder() = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(MOBILE_FILES_MOVE_HERE_TAG).fetchSemanticsNodes().isNotEmpty() &&
            runCatching { compose.onNodeWithText("No folders here.").assertIsDisplayed() }.isSuccess
    }

    private fun screenshot(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = UUID.fromString(
            requireNotNull(InstrumentationRegistry.getArguments().getString("putio.movetarget.runId")),
        )
        val directory = File(
            requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "move-target-proof-$runId",
        )
        check(directory.mkdirs() || directory.isDirectory)
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

private const val USER_ID = 900_001L
private const val OTHER_USER_ID = 900_002L
private val REMOVED_ID = FilesItemId(99L)

private fun item(id: Long, name: String, type: PutioFileType, parent: Long = 0L) =
    FilesItem(FilesItemId(id), FilesItemId(parent), name, type, 128_000_000, "2026-10-01T12:00:00Z")

private val SAMPLE_FOLDER = item(20, "Sample folder", PutioFileType.FOLDER)
private val ARCHIVE = item(21, "Archive été 東京", PutioFileType.FOLDER, parent = 20)
private val CLIP = item(14, "Sample clip.mp4", PutioFileType.VIDEO)
private val NOTES = item(15, "Sample notes.txt", PutioFileType.TEXT)
private val SHARED_VIDEO = item(13, "Harbor film.mp4", PutioFileType.VIDEO).copy(isShared = true)

private class MoveTargetRepository : FilesRepository {
    val moves = mutableListOf<Pair<FilesItemId, FilesItemId>>()
    val listed = mutableListOf<FilesItemId>()

    override suspend fun loadFolder(folderId: FilesItemId) = FilesRepositoryResult.Success(FilesPage(
        listOf(SAMPLE_FOLDER, SHARED_VIDEO, NOTES) + listOf(CLIP).filter { clip -> moves.none { it.first == clip.id } },
        null,
    ))

    override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?): FilesRepositoryResult<FilesPage> {
        listed += folderId
        return when (folderId) {
            FilesFolder.Root.id -> FilesRepositoryResult.Success(FilesPage(listOf(SAMPLE_FOLDER), null))
            SAMPLE_FOLDER.id -> FilesRepositoryResult.Success(FilesPage(listOf(ARCHIVE), null, parent = SAMPLE_FOLDER))
            ARCHIVE.id -> FilesRepositoryResult.Success(FilesPage(emptyList(), null, parent = ARCHIVE))
            else -> FilesRepositoryResult.Failure(FilesFailure.Unexpected(IllegalStateException("No such folder")))
        }
    }

    override suspend fun move(
        itemId: FilesItemId,
        destinationId: FilesItemId,
    ): FilesRepositoryResult<List<FileMoveError>> {
        moves += itemId to destinationId
        return FilesRepositoryResult.Success(emptyList())
    }

    override suspend fun resolveItem(itemId: FilesItemId) =
        FilesRepositoryResult.Success(CLIP.copy(parentId = moves.last { it.first == itemId }.second))

    override suspend fun startCopy(itemId: FilesItemId, destinationId: FilesItemId): FilesRepositoryResult<FilesCopyId> =
        error("No copy")
    override suspend fun checkCopy(copyId: FilesCopyId): FilesRepositoryResult<FilesCopyProgress> = error("No copy")
    override suspend fun loadNextPage(cursor: FilesCursor) = error("No paging")
    override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort) = error("No sort")
    override suspend fun rename(itemId: FilesItemId, name: String) = error("No rename")
    override suspend fun delete(itemId: FilesItemId, mode: FilesDeleteMode) = error("No delete")
}
