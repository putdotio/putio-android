package io.putdotio.android.trash

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesRepositoryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashSessionTest {
    @Test
    fun staleConfirmCannotRestoreAnotherItemOrAReopenedConfirmation() = runBlocking {
        for (nextItem in listOf(trashItem(), trashItem(8L))) {
            val repository = FakeTrashRepository().apply { onLoad = { page(trashItem(), trashItem(8L)) } }
            TrashController(repository, this).use { controller ->
                controller.openLoaded()
                controller.dispatch(TrashEvent.SelectRestore(trashItem().id))
                val stale = TrashEvent.ConfirmRestore(checkNotNull(controller.state.value.confirmationId))
                controller.dispatch(TrashEvent.CancelRestore)
                controller.dispatch(TrashEvent.SelectRestore(nextItem.id))
                assertFalse(controller.dispatch(stale))
                assertTrue(repository.restoredIds.isEmpty())
                val current = TrashEvent.ConfirmRestore(checkNotNull(controller.state.value.confirmationId))
                assertTrue(controller.dispatch(current))
                assertFalse(controller.dispatch(current))
                controller.awaitState { it.restoreOutcome?.check == TrashRestoreCheck.UNAVAILABLE }
                assertEquals(listOf(nextItem.id), repository.restoredIds)
            }
        }
    }

    @Test
    fun aCancelledInitialReadCannotReplaceAnAuthoritativeRefresh() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val repository = FakeTrashRepository().apply {
            onLoad = {
                if (loadCount == 1) withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                    finished.complete(Unit)
                    page(trashItem())
                } else page(trashItem(8L))
            }
        }
        TrashController(repository, this).use { controller ->
            controller.dispatch(TrashEvent.Open)
            withTimeout(5_000) { started.await() }
            controller.dispatch(TrashEvent.Refresh)
            controller.awaitState { (it.content as? TrashContent.Loaded)?.items == listOf(trashItem(8L)) }
            release.complete(Unit)
            withTimeout(5_000) { finished.await() }
            yield()
            assertEquals(listOf(trashItem(8L)), (controller.state.value.content as TrashContent.Loaded).items)
        }
    }

    @Test
    fun closingOrCancellingTheSessionIgnoresALateRestoreResponse() = runBlocking {
        for (cancelParent in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val parentJob = SupervisorJob()
            val repository = FakeTrashRepository().apply {
                onRestore = {
                    withContext(NonCancellable) {
                        started.complete(Unit)
                        release.await()
                        finished.complete(Unit)
                        FilesRepositoryResult.Success(Unit)
                    }
                }
            }
            val controller = TrashController(repository, CoroutineScope(coroutineContext + parentJob))
            try {
                controller.openLoaded()
                controller.confirm()
                withTimeout(5_000) { started.await() }
                if (cancelParent) parentJob.cancel() else controller.close()
                val previous = controller.state.value
                release.complete(Unit)
                withTimeout(5_000) { finished.await() }
                yield()
                assertEquals(previous, controller.state.value)
                assertTrue(repository.resolvedIds.isEmpty())
                assertFalse(controller.dispatch(TrashEvent.Refresh))
            } finally {
                release.complete(Unit)
                controller.close()
                parentJob.cancel()
            }
        }
    }

    @Test
    fun authenticationFailureAtEveryBoundaryIsRetainedAndBlocksNewConfirmation() = runBlocking {
        val auth = FilesFailure.AuthenticationRequired(apiFailure(401, "invalid_token").cause)
        for (stage in listOf("initial", "page", "restore", "check")) {
            val repository = FakeTrashRepository().apply {
                onLoad = {
                    if (stage == "initial") FilesRepositoryResult.Failure(auth) else
                        FilesRepositoryResult.Success(TrashPage(listOf(trashItem()), FilesCursor("a"), 1, 12L))
                }
                if (stage == "page") onPage = { FilesRepositoryResult.Failure(auth) }
                if (stage == "restore") onRestore = { FilesRepositoryResult.Failure(auth) }
                if (stage == "check") onResolve = { FilesRepositoryResult.Failure(auth) }
            }
            TrashController(repository, this).use { controller ->
                controller.dispatch(TrashEvent.Open)
                if (stage != "initial") {
                    controller.awaitState { it.content is TrashContent.Loaded }
                    if (stage == "page") controller.dispatch(TrashEvent.LoadNextPage) else controller.confirm()
                }
                val state = controller.awaitState { it.authenticationFailure != null }
                assertNotNull(state.authenticationFailure)
                assertFalse(state.canRestore(trashItem().id))
                assertFalse(controller.dispatch(TrashEvent.SelectRestore(trashItem().id)))
                assertFalse(controller.dispatch(TrashEvent.ConfirmRestore(-1L)))
                assertFalse(controller.dispatch(TrashEvent.Retry))
            }
        }
    }

    @Test
    fun permissionAndRateLimitRejectionsRemainVisibleWithoutSigningOutOrReadingBack() = runBlocking {
        val cause = apiFailure(403, "invalid_scope").cause
        for (failure in listOf(FilesFailure.AccessDenied(cause), FilesFailure.RateLimited(cause))) {
            val repository = FakeTrashRepository().apply { onRestore = { FilesRepositoryResult.Failure(failure) } }
            TrashController(repository, this).use { controller ->
                controller.openLoaded()
                controller.confirm()
                val state = controller.awaitState { it.restoreOutcome?.submission == TrashRestoreSubmission.REJECTED }
                assertEquals(failure, state.restoreOutcome?.submissionFailure)
                assertNull(state.authenticationFailure)
                assertTrue(repository.resolvedIds.isEmpty())
                assertTrue(controller.dispatch(TrashEvent.SelectRestore(trashItem().id)))
            }
        }
    }
}
