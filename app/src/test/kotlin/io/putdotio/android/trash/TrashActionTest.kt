package io.putdotio.android.trash

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepositoryResult
import kotlinx.coroutines.CompletableDeferred
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
    fun uncertainDeleteThatIsStillListedIsAnsweredWithoutResubmitting() = runBlocking {
        val repository = FakeTrashRepository().apply { onDelete = { error("lost response") } }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            controller.confirmAction(TrashEvent.SelectDelete(trashItem().id))
            val failed = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.FAILED }
            assertEquals(TrashActionSubmission.UNCERTAIN, failed.actionOutcome?.submission)
            assertNull(failed.actionOutcome?.checkFailure)
            // The complete read that still lists the item answers the question: nothing happened.
            assertFalse(failed.hasPendingAction)
            assertTrue(failed.canDelete(trashItem().id))
            assertFalse(controller.dispatch(TrashEvent.CheckAction))
            assertTrue(controller.dispatch(TrashEvent.DismissActionOutcome))
            assertEquals(1, repository.deletedIds.size)
            assertEquals(2, repository.loadCount)
        }
    }

    @Test
    fun uncertainDeleteWhoseReadFailsRetainsReadOnlyRecoveryWithoutResubmitting() = runBlocking {
        val repository = FakeTrashRepository().apply { onDelete = { error("lost response") } }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            repository.onLoad = { FilesRepositoryResult.Failure(offlineFailure()) }
            controller.confirmAction(TrashEvent.SelectDelete(trashItem().id))
            val offline = controller.awaitState { it.actionOutcome?.checkFailure != null }
            assertEquals(TrashActionCheck.FAILED, offline.actionOutcome?.check)
            assertTrue(offline.actionOutcome?.checkFailure is FilesFailure.Unexpected)
            assertTrue((offline.content as TrashContent.Loaded).refreshFailure != null)
            assertTrue(offline.hasPendingAction)
            assertFalse(offline.canRestore(trashItem().id))
            assertFalse(offline.canDelete(trashItem().id))
            assertFalse(offline.canActOnAll)
            assertFalse(controller.dispatch(TrashEvent.DismissActionOutcome))
            assertFalse(controller.dispatch(TrashEvent.SelectRestore(trashItem().id)))
            assertFalse(controller.dispatch(TrashEvent.SelectEmpty))
            repository.onLoad = { page() }
            assertTrue(controller.dispatch(TrashEvent.CheckAction))
            controller.awaitState { it.actionOutcome?.check == TrashActionCheck.VERIFIED }
            assertEquals(1, repository.deletedIds.size)
            assertEquals(3, repository.loadCount)
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
            assertFalse(stillPresent.hasPendingAction)
            assertTrue(stillPresent.canActOnAll)
            assertEquals(0L, stillPresent.bulkRestoreVersion)
            assertFalse(controller.dispatch(TrashEvent.CheckAction))
            assertTrue(controller.dispatch(TrashEvent.DismissActionOutcome))
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
            actionRepository.onLoad = { FilesRepositoryResult.Failure(offlineFailure()) }
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

    @Test
    fun refreshDuringVerificationKeepsVerifyingInsteadOfStrandingRecovery() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeTrashRepository()
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            repository.onLoad = { gate.await(); page() }
            controller.confirmAction(TrashEvent.SelectDelete(trashItem().id))
            controller.awaitState { it.actionOutcome?.check == TrashActionCheck.CHECKING }
            assertTrue(controller.dispatch(TrashEvent.Refresh))
            gate.complete(Unit)
            val verified = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.VERIFIED }
            assertFalse(verified.hasPendingAction)
            assertEquals(1, repository.deletedIds.size)
        }
    }

    @Test
    fun cancelledOrReplayedConfirmationsNeverReachTheRepository() = runBlocking {
        val repository = FakeTrashRepository().apply { onLoad = { page(trashItem(), trashItem(8L)) } }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            assertTrue(controller.dispatch(TrashEvent.SelectEmpty))
            val cancelledId = checkNotNull(controller.state.value.actionConfirmationId)
            assertTrue(controller.dispatch(TrashEvent.CancelAction))
            assertNull(controller.state.value.actionConfirmation)
            assertFalse(controller.dispatch(TrashEvent.ConfirmAction(cancelledId)))
            assertTrue(controller.dispatch(TrashEvent.SelectDelete(trashItem().id)))
            assertFalse(controller.dispatch(TrashEvent.ConfirmAction(cancelledId)))
            val liveId = checkNotNull(controller.state.value.actionConfirmationId)
            repository.onLoad = { page(trashItem(8L)) }
            assertTrue(controller.dispatch(TrashEvent.ConfirmAction(liveId)))
            assertFalse(controller.dispatch(TrashEvent.ConfirmAction(liveId)))
            controller.awaitState { it.actionOutcome?.check == TrashActionCheck.VERIFIED }
            assertFalse(controller.dispatch(TrashEvent.ConfirmAction(liveId)))
            assertEquals(0, repository.emptyCount)
            assertEquals(listOf(trashItem().id), repository.deletedIds)
        }
    }

    @Test
    fun restoreAllVerifiesAgainstItsSnapshotSoLaterDeletionsDoNotStrandRecovery() = runBlocking {
        val newer = trashItem(9L).copy(deletedAt = "2026-09-07T10:00:00")
        val older = trashItem(3L).copy(deletedAt = "2026-09-01T10:00:00")
        // Loaded IDs only: a complete page without any of them is verified whatever else arrived.
        val idsRepository = FakeTrashRepository().apply { onLoad = { page(trashItem(), trashItem(8L)) } }
        TrashController(idsRepository, this).use { controller ->
            controller.openLoaded()
            idsRepository.onLoad = { page(newer, older) }
            controller.confirmAction(TrashEvent.SelectRestoreAll)
            val verified = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.VERIFIED }
            assertFalse(verified.hasPendingAction)
            assertTrue(verified.canActOnAll)
        }
        // A cursor covers unloaded IDs too: only rows deleted after the newest loaded one are provably new.
        val cursorRepository = FakeTrashRepository().apply {
            onLoad = { FilesRepositoryResult.Success(TrashPage(listOf(trashItem()), FilesCursor("snapshot"), 80, 5L)) }
        }
        TrashController(cursorRepository, this).use { controller ->
            controller.openLoaded()
            cursorRepository.onLoad = { page(older) }
            controller.confirmAction(TrashEvent.SelectRestoreAll)
            val inconclusive = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.INCONCLUSIVE }
            assertTrue(inconclusive.hasPendingAction)
            cursorRepository.onLoad = { page(newer) }
            assertTrue(controller.dispatch(TrashEvent.CheckAction))
            val verified = controller.awaitState { it.actionOutcome?.check == TrashActionCheck.VERIFIED }
            assertFalse(verified.hasPendingAction)
            assertEquals(1, cursorRepository.bulkRestores.size)
        }
    }

    @Test
    fun aFailedRefreshWithholdsBulkActionsUntilAFreshSnapshot() = runBlocking {
        val repository = FakeTrashRepository()
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            repository.onLoad = { FilesRepositoryResult.Failure(offlineFailure()) }
            assertTrue(controller.dispatch(TrashEvent.Refresh))
            val stale = controller.awaitState { (it.content as? TrashContent.Loaded)?.refreshFailure != null }
            assertFalse(stale.canActOnAll)
            assertTrue(stale.canDelete(trashItem().id))
            assertFalse(controller.dispatch(TrashEvent.SelectRestoreAll))
            repository.onLoad = { page(trashItem()) }
            assertTrue(controller.dispatch(TrashEvent.Retry))
            val fresh = controller.awaitState {
                (it.content as? TrashContent.Loaded)?.let { c -> !c.isRefreshing && c.refreshFailure == null } == true
            }
            assertTrue(fresh.canActOnAll)
        }
    }

    private fun rejection(status: Int, type: String): FilesFailure =
        if (status == 403) FilesFailure.AccessDenied(apiFailure(status, type).cause) else apiFailure(status, type)
}

