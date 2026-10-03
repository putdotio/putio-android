package io.putdotio.android.trash

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesRepositoryResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashRepeatedRestoreTest {
    @Test
    fun laterDeletionOnInitialRefreshAllowsOneNewRestoreWhileStaleOccurrencesRemainBlocked() = runBlocking {
        val original = trashItem()
        val repository = FakeTrashRepository().apply {
            onResolve = { FilesRepositoryResult.Success(liveItem(original)) }
        }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            controller.confirm(original.id)
            controller.awaitState {
                it.restoreOutcome?.check == TrashRestoreCheck.AVAILABLE &&
                    (it.content as? TrashContent.Loaded)?.isRefreshing == false
            }
            controller.dispatch(TrashEvent.DismissRestoreOutcome)

            for (date in listOf(null, "invalid", "2026-09-06", "2026-09-05T10:00:00", original.deletedAt)) {
                repository.onLoad = { page(original.copy(deletedAt = date)) }
                controller.dispatch(TrashEvent.Refresh)
                controller.awaitState { (it.content as? TrashContent.Loaded)?.isRefreshing == false }
                assertFalse("Unproven new deletion: $date", controller.state.value.canRestore(original.id))
                assertFalse(controller.dispatch(TrashEvent.SelectRestore(original.id)))
            }

            val deletedAgain = original.copy(deletedAt = "2026-09-07T10:00:00")
            repository.onLoad = { page(deletedAgain) }
            controller.dispatch(TrashEvent.Refresh)
            controller.awaitState { (it.content as? TrashContent.Loaded)?.isRefreshing == false }
            assertTrue(controller.state.value.canRestore(original.id))

            // A later stale initial response must not undo the original occurrence's suppression.
            repository.onLoad = { page(original) }
            controller.dispatch(TrashEvent.Refresh)
            controller.awaitState { (it.content as? TrashContent.Loaded)?.isRefreshing == false }
            assertFalse(controller.state.value.canRestore(original.id))

            repository.onLoad = { page(deletedAgain) }
            repository.onResolve = { FilesRepositoryResult.Failure(apiFailure(404, "NOT_FOUND")) }
            controller.dispatch(TrashEvent.Refresh)
            controller.awaitState { (it.content as? TrashContent.Loaded)?.isRefreshing == false }
            controller.confirm(original.id)
            controller.awaitState { it.restoreOutcome?.check == TrashRestoreCheck.UNAVAILABLE }
            assertFalse(controller.dispatch(TrashEvent.SelectRestore(original.id)))
            assertEquals(listOf(original.id, original.id), repository.restoredIds)
        }
    }

    @Test
    fun continuationAndUnknownOriginalDateCannotProveANewDeletion() = runBlocking {
        for (originalDate in listOf(null, "invalid", "2026-09-06T10:00:00")) {
            val original = trashItem().copy(deletedAt = originalDate)
            val repository = FakeTrashRepository().apply {
                onLoad = { page(original) }
                onResolve = { FilesRepositoryResult.Success(liveItem(original)) }
            }
            TrashController(repository, this).use { controller ->
                controller.openLoaded()
                controller.confirm(original.id)
                controller.awaitState {
                    it.restoreOutcome?.check == TrashRestoreCheck.AVAILABLE &&
                        (it.content as? TrashContent.Loaded)?.isRefreshing == false
                }
                val later = original.copy(deletedAt = "2026-09-07T10:00:00Z")
                repository.onLoad = {
                    FilesRepositoryResult.Success(TrashPage(emptyList(), FilesCursor("next"), 1, 12))
                }
                repository.onPage = { page(later) }
                controller.dispatch(TrashEvent.Refresh)
                controller.awaitState { (it.content as? TrashContent.Loaded)?.isRefreshing == false }
                controller.dispatch(TrashEvent.LoadNextPage)
                controller.awaitState { (it.content as? TrashContent.Loaded)?.isLoadingMore == false }
                assertFalse(controller.state.value.canRestore(original.id))

                repository.onLoad = { page(later) }
                controller.dispatch(TrashEvent.Refresh)
                controller.awaitState { (it.content as? TrashContent.Loaded)?.isRefreshing == false }
                assertEquals(originalDate == "2026-09-06T10:00:00", controller.state.value.canRestore(original.id))
                assertEquals(listOf(original.id), repository.restoredIds)
            }
        }
    }
}
