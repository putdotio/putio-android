package io.putdotio.android.transfers

import io.putdotio.android.files.FilesRepositoryResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransfersControllerTest {
    @Test
    fun pollsEveryFiveSecondsOnlyWhileVisibleWithActiveRows(): Unit = runBlocking {
        val pollDelay = CompletableDeferred<Long>()
        val releasePoll = CompletableDeferred<Unit>()
        var loads = 0
        var refreshes = 0
        val repository =
            repository(
                load = {
                    loads += 1
                    FilesRepositoryResult.Success(TransfersPage(listOf(item(1L)), null))
                },
                refresh = { ids ->
                    refreshes += 1
                    FilesRepositoryResult.Success(
                        TransfersRowRefresh(ids.map { item(it.value) }, emptySet()),
                    )
                },
            )
        val controller = TransfersController(repository, this) { millis ->
            pollDelay.complete(millis)
            releasePoll.await()
        }
        try {
            controller.awaitState { it.content is TransfersContent.Ready }
            assertEquals(1, loads)
            assertTrue(controller.dispatch(TransfersEvent.VisibilityChanged(true)))
            assertEquals(5_000L, withTimeout(TIMEOUT) { pollDelay.await() })
            assertEquals(1, loads)
            releasePoll.complete(Unit)
            controller.awaitState { refreshes == 1 && it.refresh == TransfersRefresh.Idle }
            assertEquals(1, loads)
            controller.dispatch(TransfersEvent.VisibilityChanged(false))
        } finally {
            controller.close()
        }
    }

    @Test
    fun terminalRowsDoNotStartPolling(): Unit = runBlocking {
        var waits = 0
        val repository = repository {
            FilesRepositoryResult.Success(
                TransfersPage(listOf(item(1L, AppTransferStatus.Completed, TransferFileId(11L))), null),
            )
        }
        val controller = TransfersController(repository, this) { waits += 1 }
        try {
            controller.awaitState { it.content is TransfersContent.Ready }
            controller.dispatch(TransfersEvent.VisibilityChanged(true))
            assertEquals(0, waits)
        } finally {
            controller.close()
        }
    }

    @Test
    fun completedRowsWithoutFilesKeepPollingUntilResolved(): Unit = runBlocking {
        val pollDelay = CompletableDeferred<Long>()
        val repository = repository {
            FilesRepositoryResult.Success(
                TransfersPage(listOf(item(1L, AppTransferStatus.Completed)), null),
            )
        }
        val controller = TransfersController(repository, this) { millis ->
            pollDelay.complete(millis)
            awaitCancellation()
        }
        try {
            controller.awaitState { it.content is TransfersContent.Ready }
            controller.dispatch(TransfersEvent.VisibilityChanged(true))

            assertEquals(5_000L, withTimeout(TIMEOUT) { pollDelay.await() })
        } finally {
            controller.close()
        }
    }

    @Test
    fun hidingTransfersInvalidatesARefreshButKeepsItsSingleFlightBarrier(): Unit = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        var refreshes = 0
        val repository =
            repository(
                load = {
                    FilesRepositoryResult.Success(TransfersPage(listOf(item(1L)), null))
                },
                refresh = {
                    refreshes += 1
                    refreshStarted.complete(Unit)
                    if (refreshes == 1) {
                        withContext(NonCancellable) { releaseRefresh.await() }
                    }
                    FilesRepositoryResult.Success(
                        TransfersRowRefresh(listOf(item(1L)), emptySet()),
                    )
                },
            )
        val controller = TransfersController(repository, this) {}
        try {
            controller.awaitState { it.content is TransfersContent.Ready }
            assertTrue(controller.dispatch(TransfersEvent.VisibilityChanged(true)))
            withTimeout(TIMEOUT) { refreshStarted.await() }
            val requestId =
                (controller.state.value.refresh as TransfersRefresh.Polling).requestId

            assertTrue(controller.dispatch(TransfersEvent.VisibilityChanged(false)))
            val stopped = controller.awaitState { !it.visible && it.refresh == TransfersRefresh.Idle }

            assertFalse(stopped.visible)
            val retainedIds =
                (stopped.content as TransfersContent.Ready).items.map(TransferItem::id)
            assertEquals(listOf(TransferId(1L)), retainedIds)
            assertFalse(
                controller.dispatch(
                    TransfersEvent.RowsRefreshed(
                        requestId,
                        listOf(item(99L)),
                        emptySet(),
                    ),
                ),
            )
            assertEquals(stopped, controller.state.value)

            assertTrue(controller.dispatch(TransfersEvent.VisibilityChanged(true)))
            assertFalse(controller.dispatch(TransfersEvent.Refresh))
            assertEquals(1, refreshes)
            releaseRefresh.complete(Unit)
            withTimeout(TIMEOUT) {
                while (!controller.dispatch(TransfersEvent.Refresh)) kotlinx.coroutines.yield()
            }
            controller.awaitState { refreshes == 2 && it.refresh == TransfersRefresh.Idle }
        } finally {
            controller.close()
        }
    }

    @Test
    fun refreshingReconcilesRowsThatMovedAcrossALoadedPageBoundary(): Unit = runBlocking {
        var loads = 0
        var reconciledIds = emptyList<TransferId>()
        val repository =
            repository(
                refresh = { ids ->
                    reconciledIds = ids
                    FilesRepositoryResult.Success(
                        TransfersRowRefresh(ids.map { item(it.value) }, emptySet()),
                    )
                },
                load = { cursor ->
                    loads += 1
                    val page =
                        when {
                            loads == 1 -> TransfersPage(listOf(item(1L), item(2L)), TransferCursor("next"))
                            cursor != null -> TransfersPage(listOf(item(3L), item(4L)), null)
                            else -> TransfersPage(listOf(item(9L), item(1L)), TransferCursor("next"))
                        }
                    FilesRepositoryResult.Success(page)
                },
            )
        val controller = TransfersController(repository, this)
        try {
            controller.awaitState { it.content is TransfersContent.Ready }
            assertTrue(controller.dispatch(TransfersEvent.LoadNextPage))
            controller.awaitState {
                (it.content as? TransfersContent.Ready)?.paging == TransfersPaging.Complete
            }

            assertTrue(controller.dispatch(TransfersEvent.Refresh))
            val refreshed = controller.awaitState { loads == 3 && it.refresh == TransfersRefresh.Idle }

            assertEquals(
                listOf(TransferId(2L), TransferId(3L), TransferId(4L)),
                reconciledIds,
            )
            assertEquals(
                listOf(9L, 1L, 2L, 3L, 4L),
                (refreshed.content as TransfersContent.Ready).items.map { it.id.value },
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun mutationIsSingleFlightAndFailureKeepsSubmittedInput(): Unit = runBlocking {
        val addStarted = CompletableDeferred<Unit>()
        val addResult = CompletableDeferred<FilesRepositoryResult<TransferItem>>()
        val base = repository {
            FilesRepositoryResult.Success(TransfersPage(emptyList(), null))
        }
        val repository = object : TransfersRepository by base {
            override suspend fun add(submission: TransferSubmission): FilesRepositoryResult<TransferItem> {
                addStarted.complete(Unit)
                return addResult.await()
            }
        }
        val controller = TransfersController(repository, this)
        try {
            controller.awaitState { it.content == TransfersContent.Empty }
            assertTrue(controller.dispatch(TransfersEvent.Add("magnet:?xt=urn:test")))
            withTimeout(TIMEOUT) { addStarted.await() }
            assertFalse(controller.dispatch(TransfersEvent.Add("https://example.com/second")))
            val failure = io.putdotio.android.files.FilesFailure.Unexpected(IllegalStateException("offline"))
            addResult.complete(FilesRepositoryResult.Failure(failure))
            val state = controller.awaitState { it.mutation is TransferMutation.Failed }
            val action = (state.mutation as TransferMutation.Failed).action as TransferAction.Add
            assertEquals("magnet:?xt=urn:test", action.submission.value)
        } finally {
            controller.close()
        }
    }

    @Test
    fun closeCancelsInFlightReadAndRejectsLateSessionEvents(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val repository = repository {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val controller = TransfersController(repository, this)
        withTimeout(TIMEOUT) { started.await() }
        controller.close()
        withTimeout(TIMEOUT) { cancelled.await() }

        assertFalse(
            controller.dispatch(
                TransfersEvent.ListSucceeded(
                    TransfersRequestId(1L),
                    TransfersPage(listOf(item(9L)), null),
                ),
            ),
        )
        assertTrue(controller.state.value.content is TransfersContent.InitialLoading)
    }

    @Test
    fun completedFileNavigationRemainsInStateForTheHost(): Unit = runBlocking {
        val repository = repository {
            FilesRepositoryResult.Success(
                TransfersPage(listOf(item(1L, AppTransferStatus.Completed, TransferFileId(11L))), null),
            )
        }
        val controller = TransfersController(repository, this)
        try {
            controller.awaitState { it.content is TransfersContent.Ready }
            controller.dispatch(TransfersEvent.Open(TransferId(1L)))
            assertEquals(
                TransferNavigation.Resolving(TransferFileId(11L), TransfersRequestId(2L)),
                controller.state.value.navigation,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun openingAFileCancelsBackgroundPolling(): Unit = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshCancelled = CompletableDeferred<Unit>()
        val repository =
            repository(
                load = {
                    FilesRepositoryResult.Success(
                        TransfersPage(
                            listOf(item(1L, AppTransferStatus.Seeding, TransferFileId(11L))),
                            null,
                        ),
                    )
                },
                refresh = {
                    refreshStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        refreshCancelled.complete(Unit)
                    }
                },
            )
        val controller = TransfersController(repository, this) {}
        try {
            controller.awaitState { it.content is TransfersContent.Ready }
            assertTrue(controller.dispatch(TransfersEvent.VisibilityChanged(true)))
            withTimeout(TIMEOUT) { refreshStarted.await() }
            controller.awaitState { it.refresh is TransfersRefresh.Polling }

            assertTrue(controller.dispatch(TransfersEvent.Open(TransferId(1L))))
            withTimeout(TIMEOUT) { refreshCancelled.await() }

            assertEquals(TransfersRefresh.Idle, controller.state.value.refresh)
            assertEquals(
                TransferNavigation.Resolving(TransferFileId(11L), TransfersRequestId(3L)),
                controller.state.value.navigation,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun refreshCanStartImmediatelyAfterInitialLoadPublishesReady(): Unit = runBlocking {
        var loads = 0
        val repository = repository {
            loads += 1
            FilesRepositoryResult.Success(TransfersPage(listOf(item(loads.toLong())), null))
        }
        val controller = TransfersController(repository, this)
        try {
            controller.awaitState { it.content is TransfersContent.Ready }
            assertTrue(controller.dispatch(TransfersEvent.Refresh))
            controller.awaitState { loads == 2 && it.refresh == TransfersRefresh.Idle }
            assertEquals(2, loads)
        } finally {
            controller.close()
        }
    }

    @Test
    fun mutationCancelsAnOverlappingRefreshBeforePublishingItsResult(): Unit = runBlocking {
        var loads = 0
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshCancelled = CompletableDeferred<Unit>()
        val base = repository {
            loads += 1
            if (loads == 1) {
                FilesRepositoryResult.Success(TransfersPage(listOf(item(1L)), null))
            } else {
                refreshStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    refreshCancelled.complete(Unit)
                }
            }
        }
        val repository = object : TransfersRepository by base {
            override suspend fun add(submission: TransferSubmission) =
                FilesRepositoryResult.Success(item(2L, AppTransferStatus.Completed))
        }
        val controller = TransfersController(repository, this)
        try {
            controller.awaitState { it.content is TransfersContent.Ready }
            assertTrue(controller.dispatch(TransfersEvent.Refresh))
            withTimeout(TIMEOUT) { refreshStarted.await() }

            assertTrue(controller.dispatch(TransfersEvent.Add("magnet:?xt=urn:btih:test")))
            withTimeout(TIMEOUT) { refreshCancelled.await() }
            val state = controller.awaitState { it.mutation == TransferMutation.Idle }

            assertEquals(TransfersRefresh.Idle, state.refresh)
            assertEquals(
                listOf(TransferId(2L), TransferId(1L)),
                (state.content as TransfersContent.Ready).items.map(TransferItem::id),
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun mutationWaitsForANonCancellableReadToFinish(): Unit = runBlocking {
        var loads = 0
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val mutationStarted = CompletableDeferred<Unit>()
        val base = repository {
            loads += 1
            if (loads == 1) {
                FilesRepositoryResult.Success(TransfersPage(listOf(item(1L)), null))
            } else {
                withContext(NonCancellable) {
                    refreshStarted.complete(Unit)
                    releaseRefresh.await()
                }
                FilesRepositoryResult.Success(TransfersPage(listOf(item(1L)), null))
            }
        }
        val repository = object : TransfersRepository by base {
            override suspend fun add(submission: TransferSubmission): FilesRepositoryResult<TransferItem> {
                mutationStarted.complete(Unit)
                return FilesRepositoryResult.Success(item(2L))
            }
        }
        val controller = TransfersController(repository, this)
        try {
            controller.awaitState { it.content is TransfersContent.Ready }
            controller.dispatch(TransfersEvent.Refresh)
            withTimeout(TIMEOUT) { refreshStarted.await() }

            controller.dispatch(TransfersEvent.Add("magnet:?xt=urn:btih:test"))
            val overlapped = withTimeoutOrNull(100L) { mutationStarted.await() } != null
            assertFalse(overlapped)

            releaseRefresh.complete(Unit)
            withTimeout(TIMEOUT) { mutationStarted.await() }
        } finally {
            controller.close()
        }
    }

    @Test
    fun cleanAllRefreshesFromTheServerEvenWhenNoDeletedIdsAreReturned(): Unit = runBlocking {
        var loads = 0
        var cleanedIds: List<TransferId>? = null
        val completed = item(1L, AppTransferStatus.Completed)
        val active = item(2L)
        val base = repository {
            loads += 1
            val rows = if (loads == 1) listOf(completed, active) else listOf(active)
            FilesRepositoryResult.Success(TransfersPage(rows, null))
        }
        val repository = object : TransfersRepository by base {
            override suspend fun clean(ids: List<TransferId>): FilesRepositoryResult<Set<TransferId>> {
                cleanedIds = ids
                return FilesRepositoryResult.Success(emptySet())
            }
        }
        val controller = TransfersController(repository, this)
        try {
            controller.awaitState { it.content is TransfersContent.Ready }
            assertTrue(controller.dispatch(TransfersEvent.CleanCompleted))
            val refreshed = controller.awaitState { loads == 2 && it.refresh == TransfersRefresh.Idle }

            assertEquals(emptyList<TransferId>(), cleanedIds)
            assertEquals(
                listOf(active.id),
                (refreshed.content as TransfersContent.Ready).items.map(TransferItem::id),
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun secondMutationCanStartImmediatelyAfterFirstPublishesIdle(): Unit = runBlocking {
        var adds = 0
        val base = repository { FilesRepositoryResult.Success(TransfersPage(emptyList(), null)) }
        val repository = object : TransfersRepository by base {
            override suspend fun add(submission: TransferSubmission): FilesRepositoryResult<TransferItem> {
                adds += 1
                return FilesRepositoryResult.Success(item(adds.toLong()))
            }
        }
        val controller = TransfersController(repository, this)
        try {
            controller.awaitState { it.content == TransfersContent.Empty }
            assertTrue(controller.dispatch(TransfersEvent.Add("magnet:?xt=urn:first")))
            controller.awaitState { adds == 1 && it.mutation == TransferMutation.Idle }
            assertTrue(controller.dispatch(TransfersEvent.Add("magnet:?xt=urn:second")))
            controller.awaitState { adds == 2 && it.mutation == TransferMutation.Idle }
            assertEquals(2, adds)
        } finally {
            controller.close()
        }
    }

    private fun repository(
        refresh: suspend (List<TransferId>) -> FilesRepositoryResult<TransfersRowRefresh> = { ids ->
            FilesRepositoryResult.Success(TransfersRowRefresh(ids.map { item(it.value) }, emptySet()))
        },
        load: suspend (TransferCursor?) -> FilesRepositoryResult<TransfersPage>,
    ): TransfersRepository = object : TransfersRepository {
        override suspend fun load(cursor: TransferCursor?) = load(cursor)
        override suspend fun refresh(ids: List<TransferId>) = refresh(ids)
        override suspend fun add(submission: TransferSubmission) = error("Unexpected add")
        override suspend fun cancel(id: TransferId) = error("Unexpected cancel")
        override suspend fun retry(id: TransferId) = error("Unexpected retry")
        override suspend fun clean(ids: List<TransferId>) = error("Unexpected clean")
    }

    private suspend fun TransfersController.awaitState(predicate: (TransfersState) -> Boolean) =
        withTimeout(TIMEOUT) { state.first(predicate) }

    private fun item(
        id: Long,
        status: AppTransferStatus = AppTransferStatus.Downloading,
        fileId: TransferFileId? = null,
    ) = TransferItem(
        TransferId(id),
        "transfer-$id",
        status,
        fileId,
        100.0,
        40.0,
        2.0,
        1.0,
        30.0,
        1.0,
        false,
        "2026-08-30T00:00:00Z",
        null,
    )

    private companion object {
        const val TIMEOUT = 2_000L
    }
}
