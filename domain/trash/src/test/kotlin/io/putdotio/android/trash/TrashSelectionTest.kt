package io.putdotio.android.trash

import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItemId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashSelectionTest {
    private val first = trashItem(7L)
    private val second = trashItem(8L)
    private val third = trashItem(9L)
    private val ticked = setOf(third.id, first.id)

    @Test
    fun restoringTickedRowsSendsOnlyTheirIdsWithoutAsking() = runBlocking {
        val repository = FakeTrashRepository().apply { onLoad = { page(first, second, third) } }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            repository.onLoad = { page(second) }
            assertTrue(controller.dispatch(TrashEvent.RestoreSelected(ticked)))
            assertNull(controller.state.value.actionConfirmation)
            val verified = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.VERIFIED }
            assertEquals(listOf(TrashBulkSelection(cursor = null, listOf(first.id, third.id))), repository.bulkRestores)
            assertEquals(TrashAction.RestoreSelected(listOf(first, third)), verified.actionOutcome?.action)
            assertTrue(verified.bulkRestoreVersion > 0L)
        }
    }

    @Test
    fun aRestoreIsNotVerifiedWhileLaterPagesMayStillHoldItsRows() = runBlocking {
        val repository = FakeTrashRepository().apply { onLoad = { page(first, second, third) } }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            repository.onLoad = { PutioResult.Success(TrashPage(listOf(second), FilesCursor("more"), 40, 12L)) }
            assertTrue(controller.dispatch(TrashEvent.RestoreSelected(ticked)))
            val draining = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.INCONCLUSIVE }
            assertTrue(draining.hasPendingAction)
        }
    }

    @Test
    fun deletingTickedRowsAsksFirstAndNamesTheRowsThatStayed() = runBlocking {
        val repository = FakeTrashRepository().apply { onLoad = { page(first, second, third) } }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            assertTrue(controller.dispatch(TrashEvent.SelectDeleteSelected(ticked)))
            val action = TrashAction.DeleteSelected(listOf(first, third))
            assertEquals(action, controller.state.value.actionConfirmation)
            assertTrue(repository.deletedIds.isEmpty())
            repository.onLoad = { page(second, third) }
            val confirmationId = checkNotNull(controller.state.value.actionConfirmationId)
            assertTrue(controller.dispatch(TrashEvent.ConfirmAction(confirmationId)))
            val partial = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.FAILED }
            assertEquals(listOf(first.id, third.id), repository.deletedIds)
            assertEquals(1, partial.actionOutcome?.failedCount)
            assertFalse(partial.hasPendingAction)
            assertTrue(controller.dispatch(TrashEvent.SelectDeleteSelected(setOf(third.id))))
            assertEquals(TrashAction.DeleteSelected(listOf(third)), controller.state.value.actionConfirmation)
        }
    }

    @Test
    fun aSelectionWithARowNoLongerListedIsRefused() = runBlocking {
        val repository = FakeTrashRepository().apply { onLoad = { page(first, second) } }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            val gone = setOf(first.id, FilesItemId(99L))
            assertFalse(controller.dispatch(TrashEvent.RestoreSelected(gone)))
            assertFalse(controller.dispatch(TrashEvent.SelectDeleteSelected(gone)))
            assertFalse(controller.dispatch(TrashEvent.RestoreSelected(emptySet())))
            assertTrue(repository.bulkRestores.isEmpty())
        }
    }
}
