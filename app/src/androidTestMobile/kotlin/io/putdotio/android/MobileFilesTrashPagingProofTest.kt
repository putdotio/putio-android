package io.putdotio.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesCopyId
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.MOBILE_FILES_LIST_TAG
import io.putdotio.android.files.MobileFilesScreen
import io.putdotio.android.files.toFilesFailure
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Synthetic proof of a confirm-free Move to trash and of Files paging on scroll. The production
 * Files screen and controller run over an in-memory repository; nothing calls the API.
 */
@RunWith(AndroidJUnit4::class)
class MobileFilesTrashPagingProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Synthetic Files trash and paging proof requires opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.files.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun moveToTrashAnnouncesWithoutAConfirmationAndPagesLoadOnScroll() {
        val repository = InMemoryFiles()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = FilesBrowserController(repository, scope)
        var viewedTrash = 0
        try {
            compose.setContent {
                val state by controller.state.collectAsStateWithLifecycle()
                PutioTheme {
                    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        MobileFilesScreen(
                            state = state,
                            onEvent = { controller.dispatch(it) },
                            onPlayMedia = {},
                            confirmedTrashEnabled = true,
                            onViewTrash = { viewedTrash++ },
                        )
                    }
                }
            }
            val first = repository.name(1)
            compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText(first).fetchSemanticsNodes().isNotEmpty() }
            screenshot("01-first-page")

            compose.onNodeWithContentDescription("Actions for $first").performClick()
            compose.onNodeWithText("Move to trash").assertIsDisplayed()
            compose.waitForIdle()
            screenshot("02-actions")
            // Leaving the sheet is the only way not to trash now; it sends nothing.
            Espresso.pressBack()
            compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("Move to trash").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle { assertEquals(emptyList<Pair<Long, FilesDeleteMode>>(), repository.deletes.toList()) }
            compose.onNodeWithContentDescription("Actions for $first").performClick()
            compose.onNodeWithText("Move to trash").performClick()
            compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("Moved to Trash").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Confirm").assertCountEquals(0)
            compose.onAllNodesWithText("Undo").assertCountEquals(0)
            compose.onAllNodesWithText(first).assertCountEquals(0)
            screenshot("03-moved-to-trash")
            compose.onNodeWithText("View Trash").performClick()
            compose.runOnIdle {
                assertEquals(1, viewedTrash)
                assertEquals(listOf(1L to FilesDeleteMode.TRASH), repository.deletes.toList())
                assertEquals("no page beyond the first before scrolling", 0, repository.pageReads.get())
            }

            val list = compose.onNodeWithTag(MOBILE_FILES_LIST_TAG)
            list.performScrollToIndex(PAGE_SIZE - 10)
            compose.waitUntil(TIMEOUT) { repository.pageReads.get() == 1 }
            list.performScrollToNode(hasText(repository.name(PAGE_SIZE + 5)))
            screenshot("04-second-page-loaded")
            list.performScrollToNode(hasText(repository.name(TOTAL)))
            compose.waitUntil(TIMEOUT) { repository.pageReads.get() == 2 }
            compose.onAllNodesWithText("Load more").assertCountEquals(0)
            screenshot("05-last-page")
        } finally {
            compose.runOnIdle { controller.close() }
            scope.cancel()
        }
    }

    private class InMemoryFiles : FilesRepository {
        val deletes = CopyOnWriteArrayList<Pair<Long, FilesDeleteMode>>()
        val pageReads = AtomicInteger()
        private val items = CopyOnWriteArrayList((1L..TOTAL.toLong()).map(::item))

        fun name(index: Int) = "Sample ${index.toString().padStart(3, '0')}.mp4"

        private fun item(id: Long) = FilesItem(
            FilesItemId(id), FilesFolder.Root.id, name(id.toInt()), PutioFileType.VIDEO, 4_096L, "2026-09-30T10:00:00Z",
        )

        private fun page(offset: Int): FilesPage {
            val next = offset + PAGE_SIZE
            return FilesPage(items.drop(offset).take(PAGE_SIZE), FilesCursor("offset-$next").takeIf { next < items.size })
        }

        override suspend fun loadFolder(folderId: FilesItemId) = FilesRepositoryResult.Success(page(0))

        override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> {
            pageReads.incrementAndGet()
            return FilesRepositoryResult.Success(page(cursor.value.removePrefix("offset-").toInt()))
        }

        override suspend fun delete(itemId: FilesItemId, mode: FilesDeleteMode): FilesRepositoryResult<FileDeleteResult> {
            deletes += itemId.value to mode
            items.removeAll { it.id == itemId }
            return FilesRepositoryResult.Success(FileDeleteResult(status = "OK"))
        }

        override suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem> =
            items.firstOrNull { it.id == itemId }?.let { FilesRepositoryResult.Success(it) }
                ?: FilesRepositoryResult.Failure(notFound(itemId))

        override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) = error("No move")

        override suspend fun startCopy(itemId: FilesItemId, destinationId: FilesItemId) = error("No copy")

        override suspend fun checkCopy(copyId: FilesCopyId) = error("No copy")
        override suspend fun move(itemId: FilesItemId, destinationId: FilesItemId):
            FilesRepositoryResult<List<FileMoveError>> = error("No move")
        override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort) = error("No sort")
        override suspend fun rename(itemId: FilesItemId, name: String) = error("No rename")

        private fun notFound(itemId: FilesItemId) = PutioApiException(
            request = PutioRequestData("GET", "https://api.put.io/v2/files/${itemId.value}"),
            resolvedStatusCode = NOT_FOUND,
            httpStatusCode = NOT_FOUND,
            resolvedErrorType = "NotFound",
            envelope = PutioApiErrorEnvelope(statusCode = NOT_FOUND, errorType = "NotFound"),
            responseBody = "{}",
            message = "Synthetic missing item",
        ).toFilesFailure()
    }

    private fun screenshot(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = UUID.fromString(requireNotNull(InstrumentationRegistry.getArguments().getString("putio.files.runId")))
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "files-proof-$runId")
        check(directory.mkdirs() || directory.isDirectory)
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val TOTAL = 120
        const val NOT_FOUND = 404
        const val TIMEOUT = 10_000L
    }
}
