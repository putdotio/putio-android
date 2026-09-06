package io.putdotio.android.trash

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashRestoreTest {
    @Test
    fun cancelSendsNothingAndDuplicateConfirmSubmitsExactlyOneItem() = runBlocking {
        val repository = FakeTrashRepository()
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            controller.dispatch(TrashEvent.SelectRestore(trashItem().id))
            assertTrue(controller.dispatch(TrashEvent.CancelRestore))
            assertFalse(controller.dispatch(TrashEvent.ConfirmRestore(-1L)))
            assertTrue(repository.restoredIds.isEmpty())
            controller.confirm()
            assertFalse(controller.dispatch(TrashEvent.ConfirmRestore(-1L)))
            controller.awaitState { it.restoreOutcome?.check == TrashRestoreCheck.UNAVAILABLE }
            assertEquals(listOf(trashItem().id), repository.restoredIds)
            assertTrue(controller.state.value.hasPendingRestore)
            assertFalse(controller.state.value.canRestore(trashItem().id))
            assertFalse(controller.dispatch(TrashEvent.DismissRestoreOutcome))
        }
    }

    @Test
    fun acknowledgementThen404ThenRenamedRootFallbackSuccessUsesOnlyReadRetries() = runBlocking {
        val repository = FakeTrashRepository()
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            controller.confirm()
            val pending = controller.awaitState { it.restoreOutcome?.check == TrashRestoreCheck.UNAVAILABLE }
            assertEquals(TrashRestoreSubmission.ACKNOWLEDGED, pending.restoreOutcome?.submission)
            val resolved = liveItem().copy(name = "collision-2.txt", parentId = FilesItemId(0L))
            repository.onResolve = { FilesRepositoryResult.Success(resolved) }
            repository.onLoad = { FilesRepositoryResult.Failure(offlineFailure()) }
            assertTrue(controller.dispatch(TrashEvent.CheckRestore))
            val failedRefresh = controller.awaitState { (it.content as? TrashContent.Loaded)?.refreshFailure != null }
            assertEquals(resolved, failedRefresh.restoreOutcome?.resolvedItem)
            assertEquals(TrashRestoreCheck.AVAILABLE, failedRefresh.restoreOutcome?.check)
            assertEquals(1L, failedRefresh.restoredVersion)
            assertFalse(failedRefresh.hasPendingRestore)
            assertFalse(failedRefresh.canRestore(trashItem().id))
            repository.onLoad = { page(trashItem()) }
            assertTrue(controller.dispatch(TrashEvent.Retry))
            controller.awaitState { (it.content as? TrashContent.Loaded)?.isRefreshing == false }
            assertTrue(controller.dispatch(TrashEvent.DismissRestoreOutcome))
            assertEquals(resolved, controller.state.value.lastRestoredItem)
            assertFalse(controller.dispatch(TrashEvent.SelectRestore(trashItem().id)))
            assertEquals(1, repository.restoredIds.size)
            assertEquals(2, repository.resolvedIds.size)
        }
    }

    @Test
    fun ambiguousSubmissionAndTrashAbsenceNeverAuthorizeAnotherRestore() = runBlocking {
        val repository = FakeTrashRepository().apply { onRestore = { error("lost response") } }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            controller.confirm()
            val pending = controller.awaitState { it.restoreOutcome?.check == TrashRestoreCheck.UNAVAILABLE }
            assertEquals(TrashRestoreSubmission.UNCERTAIN, pending.restoreOutcome?.submission)
            assertTrue(pending.restoreOutcome?.submissionFailure is FilesFailure.Unexpected)
            repository.onLoad = { page() }
            controller.dispatch(TrashEvent.Refresh)
            val empty = controller.awaitState { (it.content as? TrashContent.Loaded)?.items?.isEmpty() == true }
            assertTrue(empty.hasPendingRestore)
            assertFalse(controller.dispatch(TrashEvent.SelectRestore(trashItem().id)))
            assertTrue(controller.dispatch(TrashEvent.CheckRestore))
            controller.awaitState { it.restoreOutcome?.check == TrashRestoreCheck.UNAVAILABLE }
            assertEquals(1, repository.restoredIds.size)
            assertEquals(2, repository.resolvedIds.size)
        }
    }

    @Test
    fun invalidReadIdentityKindOrParentRetainsRecovery() = runBlocking {
        val variants = listOf(
            liveItem().copy(id = FilesItemId(88L)), liveItem().copy(parentId = null),
            liveItem().copy(parentId = FilesItemId(-1L)), liveItem().copy(type = PutioFileType.FOLDER),
        )
        for (invalid in variants) {
            val repository = FakeTrashRepository().apply { onResolve = { FilesRepositoryResult.Success(invalid) } }
            TrashController(repository, this).use { controller ->
                controller.openLoaded()
                controller.confirm()
                val state = controller.awaitState { it.restoreOutcome?.check == TrashRestoreCheck.FAILED }
                assertTrue(state.restoreOutcome?.checkFailure is FilesFailure.InvalidResponse)
                assertTrue(state.hasPendingRestore)
                assertEquals(0L, state.restoredVersion)
            }
        }
    }

    @Test
    fun incompleteTrashIsRejectedBeforeQueueAndAllowsOnlyANewExplicitConfirmation() = runBlocking {
        val repository = FakeTrashRepository().apply {
            onRestore = { FilesRepositoryResult.Failure(apiFailure(400, "TRASH_INCOMPLETE_TRASH")) }
        }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            controller.confirm()
            val rejected = controller.awaitState { it.restoreOutcome?.submission == TrashRestoreSubmission.REJECTED }
            assertFalse(rejected.hasPendingRestore)
            assertNotNull(rejected.restoreOutcome?.submissionFailure)
            assertFalse(controller.dispatch(TrashEvent.CheckRestore))
            assertFalse(controller.dispatch(TrashEvent.ConfirmRestore(-1L)))
            assertTrue(repository.resolvedIds.isEmpty())
            repository.onRestore = { FilesRepositoryResult.Success(Unit) }
            controller.confirm()
            controller.awaitState { it.restoreOutcome?.check == TrashRestoreCheck.UNAVAILABLE }
            assertEquals(2, repository.restoredIds.size)
        }
    }

    @Test
    fun trashNotFoundMutationReconcilesInsteadOfClaimingSuccess() = runBlocking {
        val repository = FakeTrashRepository().apply {
            onRestore = { FilesRepositoryResult.Failure(apiFailure(404, "TRASH_FILE_NOT_FOUND")) }
        }
        TrashController(repository, this).use { controller ->
            controller.openLoaded()
            controller.confirm()
            val state = controller.awaitState { it.restoreOutcome?.check == TrashRestoreCheck.UNAVAILABLE }
            assertTrue(state.hasPendingRestore)
            assertEquals(1, repository.restoredIds.size)
            assertEquals(1, repository.resolvedIds.size)
        }
    }
}
