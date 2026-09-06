package io.putdotio.android.trash

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesRepositoryResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashPaginationTest {
    @Test
    fun openIsLazyAndDoesNotReloadTheSessionOnReentry() = runBlocking {
        val repository = FakeTrashRepository()
        TrashController(repository, this).use { controller ->
            assertEquals(0, repository.loadCount)
            controller.openLoaded()
            assertFalse(controller.dispatch(TrashEvent.Open))
            assertEquals(1, repository.loadCount)
        }
    }

    @Test
    fun overlappingEmptyPagesPreserveFirstSeenItemsAndInitialAggregatesAndStopCursorCycles() = runBlocking {
        val original = trashItem()
        val repository = FakeTrashRepository().apply {
            onLoad = {
                FilesRepositoryResult.Success(TrashPage(listOf(original, original), FilesCursor("a"), 20, 123L))
            }
            onPage = { cursor ->
                val items = if (cursor.value == "a") {
                    listOf(original.copy(name = "stale"), trashItem(8L))
                } else emptyList()
                FilesRepositoryResult.Success(TrashPage(items, FilesCursor(if (cursor.value == "a") "b" else "a")))
            }
        }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            repeat(2) {
                assertTrue(controller.dispatch(TrashEvent.LoadNextPage))
                assertFalse(controller.dispatch(TrashEvent.LoadNextPage))
                controller.awaitState { state -> (state.content as? TrashContent.Loaded)?.isLoadingMore == false }
            }
            val content = controller.state.value.content as TrashContent.Loaded
            assertEquals(listOf(original, trashItem(8L)), content.items)
            assertEquals(20, content.total)
            assertEquals(123L, content.trashSizeBytes)
            assertNull(content.nextCursor)
            assertFalse(controller.dispatch(TrashEvent.LoadNextPage))
            assertEquals(listOf(FilesCursor("a"), FilesCursor("b")), repository.pageCursors)
        }
    }

    @Test
    fun pageFailureRetriesTheSameCursorAndRefreshReplacesAggregates() = runBlocking {
        val repository = FakeTrashRepository().apply {
            onLoad = { FilesRepositoryResult.Success(TrashPage(listOf(trashItem()), FilesCursor("a"), 20, 123L)) }
            onPage = { FilesRepositoryResult.Failure(offlineFailure()) }
        }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            controller.dispatch(TrashEvent.LoadNextPage)
            controller.awaitState { (it.content as? TrashContent.Loaded)?.pageFailure != null }
            repository.onPage = { page(trashItem(8L)) }
            assertTrue(controller.dispatch(TrashEvent.Retry))
            controller.awaitState { (it.content as? TrashContent.Loaded)?.items?.size == 2 }
            assertEquals(listOf(FilesCursor("a"), FilesCursor("a")), repository.pageCursors)
            repository.onLoad = { FilesRepositoryResult.Success(TrashPage(emptyList(), null, 0, 0L)) }
            controller.dispatch(TrashEvent.Refresh)
            val state = controller.awaitState { (it.content as? TrashContent.Loaded)?.total == 0 }
            val content = state.content as TrashContent.Loaded
            assertTrue(content.items.isEmpty())
            assertEquals(0L, content.trashSizeBytes)
        }
    }

    @Test
    fun initialFailureHasReadOnlyRetryAndARefreshFailurePreservesContent() = runBlocking {
        val repository = FakeTrashRepository().apply { onLoad = { FilesRepositoryResult.Failure(offlineFailure()) } }
        TrashController(repository, this).use { controller ->
            controller.dispatch(TrashEvent.Open)
            controller.awaitState { it.content is TrashContent.Error }
            repository.onLoad = { page(trashItem()) }
            controller.dispatch(TrashEvent.Retry)
            controller.awaitState { it.content is TrashContent.Loaded }
            repository.onLoad = { FilesRepositoryResult.Failure(offlineFailure()) }
            controller.dispatch(TrashEvent.Refresh)
            val state = controller.awaitState { (it.content as? TrashContent.Loaded)?.refreshFailure != null }
            assertEquals(listOf(trashItem()), (state.content as TrashContent.Loaded).items)
            assertTrue(repository.restoredIds.isEmpty())
        }
    }
}
