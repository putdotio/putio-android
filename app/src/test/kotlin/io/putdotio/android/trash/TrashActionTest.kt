package io.putdotio.android.trash

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepositoryResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashActionTest {
    @Test
    fun deleteConfirmsExactlyOnceAndVerifiesAgainstAFreshFirstPage() = runBlocking {
        val repository = FakeTrashRepository().apply { onLoad = { page(trashItem(), trashItem(8L)) } }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            assertTrue(controller.dispatch(TrashEvent.SelectDelete(trashItem().id)))
            assertTrue(controller.dispatch(TrashEvent.CancelAction))
            assertFalse(controller.dispatch(TrashEvent.ConfirmAction(-1L)))
            assertTrue(repository.deletedIds.isEmpty())
            repository.onLoad = { page(trashItem(8L)) }
            controller.confirmAction(TrashEvent.SelectDelete(trashItem().id))
            assertFalse(controller.dispatch(TrashEvent.ConfirmAction(-1L)))
            val verified = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.VERIFIED }
            assertEquals(listOf(trashItem().id), repository.deletedIds)
            assertEquals(2, repository.loadCount)
            assertEquals(TrashActionSubmission.ACKNOWLEDGED, verified.actionOutcome?.submission)
            assertFalse(verified.hasPendingAction)
            assertEquals(listOf(trashItem(8L)), (verified.content as TrashContent.Loaded).items)
            assertEquals(0L, verified.bulkRestoreVersion)
            assertTrue(controller.dispatch(TrashEvent.DismissActionOutcome))
            assertNull(controller.state.value.actionOutcome)
        }
    }

    @Test
    fun uncertainDeleteThatIsStillListedRetainsReadOnlyRecoveryWithoutResubmitting() = runBlocking {
        val repository = FakeTrashRepository().apply { onDelete = { error("lost response") } }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            controller.confirmAction(TrashEvent.SelectDelete(trashItem().id))
            val failed = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.FAILED }
            assertEquals(TrashActionSubmission.UNCERTAIN, failed.actionOutcome?.submission)
            assertNull(failed.actionOutcome?.checkFailure)
            assertTrue(failed.hasPendingAction)
            assertFalse(failed.canRestore(trashItem().id))
            assertFalse(failed.canDelete(trashItem().id))
            assertFalse(failed.canActOnAll)
            assertFalse(controller.dispatch(TrashEvent.DismissActionOutcome))
            assertFalse(controller.dispatch(TrashEvent.SelectRestore(trashItem().id)))
            assertFalse(controller.dispatch(TrashEvent.SelectEmpty))
            repository.onLoad = { FilesRepositoryResult.Failure(offlineFailure()) }
            assertTrue(controller.dispatch(TrashEvent.CheckAction))
            val offline = controller.awaitState { it.actionOutcome?.checkFailure != null }
            assertTrue(offline.actionOutcome?.checkFailure is FilesFailure.Unexpected)
            assertTrue((offline.content as TrashContent.Loaded).refreshFailure != null)
            repository.onLoad = { page() }
            assertTrue(controller.dispatch(TrashEvent.CheckAction))
            controller.awaitState { it.actionOutcome?.check == TrashActionCheck.VERIFIED }
            assertEquals(1, repository.deletedIds.size)
            assertEquals(4, repository.loadCount)
        }
    }

    @Test
    fun deleteAbsentFromAPartialFirstPageIsInconclusiveUntilTrashSaysSo() = runBlocking {
        // The deleted item lives on page two; after an uncertain submission the fresh first page
        // still has a cursor, so its absence there proves nothing.
        val repository = FakeTrashRepository().apply {
            onDelete = { FilesRepositoryResult.Failure(offlineFailure()) }
            onLoad = { FilesRepositoryResult.Success(TrashPage(listOf(trashItem(8L)), FilesCursor("more"), 60, 1L)) }
            onPage = { page(trashItem()) }
        }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            assertTrue(controller.dispatch(TrashEvent.LoadNextPage))
            controller.awaitState { (it.content as? TrashContent.Loaded)?.nextCursor == null }
            controller.confirmAction(TrashEvent.SelectDelete(trashItem().id))
            val inconclusive = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.INCONCLUSIVE }
            assertTrue(inconclusive.hasPendingAction)
            repository.onLoad = { page(trashItem(8L)) }
            assertTrue(controller.dispatch(TrashEvent.CheckAction))
            controller.awaitState { it.actionOutcome?.check == TrashActionCheck.VERIFIED }
            Unit
        }
    }

    @Test
    fun knownRejectionsStopWithoutAnyVerificationRead() = runBlocking {
        for (status in listOf(400 to "TRASH_FILE_NOT_FOUND", 404 to "TRASH_FILE_NOT_FOUND", 403 to "forbidden")) {
            val repository = FakeTrashRepository().apply {
                onDelete = { FilesRepositoryResult.Failure(rejection(status.first, status.second)) }
            }
            TrashController(repository, this).use { controller ->
                controller.openLoaded()
                controller.confirmAction(TrashEvent.SelectDelete(trashItem().id))
                val rejected = controller.awaitState { it.actionOutcome?.submission == TrashActionSubmission.REJECTED }
                assertEquals(TrashActionCheck.NOT_CHECKED, rejected.actionOutcome?.check)
                assertFalse(rejected.hasPendingAction)
                assertEquals(1, repository.loadCount)
                assertTrue(controller.dispatch(TrashEvent.DismissActionOutcome))
                assertTrue(rejected.canDelete(trashItem().id))
            }
        }
    }

    @Test
    fun restoreAllUsesTheInitialSnapshotCursorOrTheLoadedIdsAndInvalidatesFiles() = runBlocking {
        val cursorRepository = FakeTrashRepository().apply {
            onLoad = { FilesRepositoryResult.Success(TrashPage(listOf(trashItem()), FilesCursor("snapshot"), 80, 5L)) }
            onPage = { page(trashItem(8L)) }
        }
        TrashController(cursorRepository, this).use { controller ->
            controller.openLoaded()
            assertTrue(controller.dispatch(TrashEvent.LoadNextPage))
            controller.awaitState { (it.content as? TrashContent.Loaded)?.nextCursor == null }
            cursorRepository.onLoad = { page(trashItem(8L)) }
            controller.confirmAction(TrashEvent.SelectRestoreAll)
            val inconclusive = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.INCONCLUSIVE }
            assertEquals(listOf(TrashBulkSelection(FilesCursor("snapshot"), listOf(trashItem().id, trashItem(8L).id))),
                cursorRepository.bulkRestores)
            assertEquals(1L, inconclusive.bulkRestoreVersion)
            assertTrue(inconclusive.hasPendingAction)
            cursorRepository.onLoad = { page() }
            assertTrue(controller.dispatch(TrashEvent.CheckAction))
            val verified = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.VERIFIED }
            assertEquals(2L, verified.bulkRestoreVersion)
            assertEquals(1, cursorRepository.bulkRestores.size)
        }
        val idsRepository = FakeTrashRepository().apply { onLoad = { page(trashItem(), trashItem(8L)) } }
        TrashController(idsRepository, this).use { controller ->
            controller.openLoaded()
            idsRepository.onLoad = { page() }
            controller.confirmAction(TrashEvent.SelectRestoreAll)
            controller.awaitState { it.actionOutcome?.check == TrashActionCheck.VERIFIED }
            val expected = TrashBulkSelection(null, listOf(trashItem().id, trashItem(8L).id))
            assertEquals(expected, idsRepository.bulkRestores.single())
        }
    }

    @Test
    fun emptyIsRefusedOnKnownEmptyTrashAndFailsVerificationWhileItemsRemain() = runBlocking {
        val emptyRepository = FakeTrashRepository().apply { onLoad = { page() } }
        TrashController(emptyRepository, this).use { controller ->
            controller.openLoaded()
            assertFalse(controller.state.value.canActOnAll)
            assertFalse(controller.dispatch(TrashEvent.SelectEmpty))
            assertFalse(controller.dispatch(TrashEvent.SelectRestoreAll))
            assertEquals(0, emptyRepository.emptyCount)
        }
        val repository = FakeTrashRepository()
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            controller.confirmAction(TrashEvent.SelectEmpty)
            val stillPresent = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.FAILED }
            assertEquals(1, repository.emptyCount)
            assertEquals(TrashActionSubmission.ACKNOWLEDGED, stillPresent.actionOutcome?.submission)
            assertTrue(stillPresent.hasPendingAction)
            assertEquals(0L, stillPresent.bulkRestoreVersion)
            repository.onLoad = { page() }
            assertTrue(controller.dispatch(TrashEvent.CheckAction))
            controller.awaitState { it.actionOutcome?.check == TrashActionCheck.VERIFIED }
            assertEquals(1, repository.emptyCount)
        }
    }

    @Test
    fun pendingSingleRestoreAndPendingActionExcludeEachOther() = runBlocking {
        val repository = FakeTrashRepository().apply { onLoad = { page(trashItem(), trashItem(8L)) } }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            controller.confirm()
            controller.awaitState { it.restoreOutcome?.check == TrashRestoreCheck.UNAVAILABLE }
            assertFalse(controller.dispatch(TrashEvent.SelectDelete(trashItem(8L).id)))
            assertFalse(controller.dispatch(TrashEvent.SelectEmpty))
            assertFalse(controller.dispatch(TrashEvent.SelectRestoreAll))
            assertTrue(repository.deletedIds.isEmpty())
        }
        val actionRepository = FakeTrashRepository().apply {
            onLoad = { page(trashItem(), trashItem(8L)) }
            onDelete = { error("lost response") }
        }
        TrashController(actionRepository, this).use { controller ->
            controller.openLoaded()
            controller.confirmAction(TrashEvent.SelectDelete(trashItem().id))
            controller.awaitState { it.actionOutcome?.check == TrashActionCheck.FAILED }
            assertFalse(controller.dispatch(TrashEvent.SelectRestore(trashItem(8L).id)))
            assertTrue(actionRepository.restoredIds.isEmpty())
        }
    }

    @Test
    fun authenticationFailureOnTheActionClosesTheSession() = runBlocking {
        val repository = FakeTrashRepository().apply {
            onEmpty = {
                val expired = FilesFailure.AuthenticationRequired(apiFailure(401, "invalid_token").cause)
                FilesRepositoryResult.Failure(expired)
            }
        }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            controller.confirmAction(TrashEvent.SelectEmpty)
            val closed = controller.awaitState { it.authenticationFailure != null }
            assertEquals(TrashActionSubmission.REJECTED, closed.actionOutcome?.submission)
            assertFalse(controller.dispatch(TrashEvent.CheckAction))
            assertFalse(controller.dispatch(TrashEvent.Refresh))
        }
    }

    private fun rejection(status: Int, type: String): FilesFailure =
        if (status == 403) FilesFailure.AccessDenied(apiFailure(status, type).cause) else apiFailure(status, type)
}

