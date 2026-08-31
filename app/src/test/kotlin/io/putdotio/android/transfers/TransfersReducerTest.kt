package io.putdotio.android.transfers

import io.putdotio.android.files.FilesFailure
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransfersReducerTest {
    @Test
    fun startsWithInitialLoadAndExposesEmptyAndFailure() {
        val start = TransfersReducer.start()
        assertNull((start.effect as TransfersEffect.Load).cursor)

        val empty = start.complete(TransfersPage(emptyList(), null))
        assertEquals(TransfersContent.Empty, empty.content)

        val failure = failure()
        val failed = TransfersReducer.reduce(start.state, TransfersEvent.ListFailed(start.requestId(), failure))
        assertEquals(failure, (failed.state.content as TransfersContent.Failed).failure)
        assertTrue(TransfersReducer.reduce(failed.state, TransfersEvent.RetryLoad).effect is TransfersEffect.Load)
    }

    @Test
    fun pagingDeduplicatesAndStopsCursorCycles() {
        val loaded = TransfersReducer.start().complete(TransfersPage(listOf(item(2L)), TransferCursor("one")))
        val loading = TransfersReducer.reduce(loaded, TransfersEvent.LoadNextPage)
        val request = loading.effect as TransfersEffect.Load
        val cycled =
            TransfersReducer.reduce(
                loading.state,
                TransfersEvent.ListSucceeded(
                    request.requestId,
                    TransfersPage(listOf(item(2L), item(1L)), TransferCursor("one")),
                ),
            ).state

        val ready = cycled.content as TransfersContent.Ready
        assertEquals(listOf(2L, 1L), ready.items.map { it.id.value })
        assertEquals(TransfersPaging.Complete, ready.paging)
        assertFalse(TransfersReducer.reduce(cycled, TransfersEvent.LoadNextPage).consumed)
    }

    @Test
    fun refreshFailureRetainsRowsAndStaleResultsAreIgnored() {
        val loaded = TransfersReducer.start().complete(TransfersPage(listOf(item(2L)), null))
        val refreshing = TransfersReducer.reduce(loaded, TransfersEvent.Refresh)
        val request = refreshing.effect as TransfersEffect.Load
        val stale =
            TransfersReducer.reduce(
                refreshing.state,
                TransfersEvent.ListSucceeded(
                    TransfersRequestId(999L),
                    TransfersPage(emptyList(), null),
                ),
            )
        assertFalse(stale.consumed)
        assertEquals(refreshing.state, stale.state)

        val failed = TransfersReducer.reduce(refreshing.state, TransfersEvent.ListFailed(request.requestId, failure()))
        val retainedItems = (failed.state.content as TransfersContent.Ready).items
        assertEquals(listOf(TransferId(2L)), retainedItems.map(TransferItem::id))
        assertTrue(failed.state.refresh is TransfersRefresh.Failed)
    }

    @Test
    fun firstPageRefreshReconcilesEveryLoadedPageAndReopensPagination() {
        val firstPage =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(1L), item(3L)), TransferCursor("one")),
            )
        val loadingNext = TransfersReducer.reduce(firstPage, TransfersEvent.LoadNextPage)
        val loadedNext =
            TransfersReducer.reduce(
                loadingNext.state,
                TransfersEvent.ListSucceeded(
                    (loadingNext.effect as TransfersEffect.Load).requestId,
                    TransfersPage(listOf(item(2L)), TransferCursor("two")),
                ),
            ).state
        val refreshing = TransfersReducer.reduce(loadedNext, TransfersEvent.Refresh)
        val refreshed =
            TransfersReducer.reduce(
                refreshing.state,
                TransfersEvent.FirstPageRefreshed(
                    (refreshing.effect as TransfersEffect.Load).requestId,
                    TransfersPage(listOf(item(1L, AppTransferStatus.Completed)), TransferCursor("one")),
                    reconciledItems = listOf(item(2L)),
                ),
            ).state

        val ready = refreshed.content as TransfersContent.Ready
        assertEquals(listOf(TransferId(1L), TransferId(2L)), ready.items.map(TransferItem::id))
        assertEquals(AppTransferStatus.Completed, ready.items.first().status)
        assertEquals(TransfersPaging.Available(TransferCursor("one")), ready.paging)
        assertEquals(setOf(TransferId(1L)), refreshed.firstPageIds)
        assertEquals(emptySet<TransferCursor>(), refreshed.consumedCursors)

        val loadingRefreshedCursor = TransfersReducer.reduce(refreshed, TransfersEvent.LoadNextPage)
        assertEquals(TransferCursor("one"), (loadingRefreshedCursor.effect as TransfersEffect.Load).cursor)
    }

    @Test
    fun paginatedRefreshDropsEveryRowWhenTheServerIsEmpty() {
        val first =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(1L)), TransferCursor("one")),
            )
        val loading = TransfersReducer.reduce(first, TransfersEvent.LoadNextPage)
        val loaded =
            TransfersReducer.reduce(
                loading.state,
                TransfersEvent.ListSucceeded(
                    (loading.effect as TransfersEffect.Load).requestId,
                    TransfersPage(listOf(item(2L)), null),
                ),
            ).state
        val refreshing = TransfersReducer.reduce(loaded, TransfersEvent.Refresh)

        val refreshed =
            TransfersReducer.reduce(
                refreshing.state,
                TransfersEvent.FirstPageRefreshed(
                    (refreshing.effect as TransfersEffect.Load).requestId,
                    TransfersPage(emptyList(), null),
                    reconciledItems = emptyList(),
                ),
            ).state

        assertEquals(TransfersContent.Empty, refreshed.content)
        assertEquals(emptySet<TransferId>(), refreshed.firstPageIds)
        assertEquals(emptySet<TransferCursor>(), refreshed.consumedCursors)
    }

    @Test
    fun addFailurePreservesInputAndMutationResultsAreRequestScoped() {
        val loaded = TransfersReducer.start().complete(TransfersPage(listOf(item(2L)), null))
        val adding = TransfersReducer.reduce(loaded, TransfersEvent.Add("  magnet:?xt=urn:abc  "))
        val request = adding.effect as TransfersEffect.Mutate
        assertEquals(submission("magnet:?xt=urn:abc"), (request.action as TransferAction.Add).submission)
        assertFalse(TransfersReducer.reduce(adding.state, TransfersEvent.Cancel(TransferId(2L))).consumed)

        val stale =
            TransfersReducer.reduce(
                adding.state,
                TransfersEvent.MutationSucceeded(TransfersRequestId(99L), item(3L)),
            )
        assertFalse(stale.consumed)
        val failed = TransfersReducer.reduce(adding.state, TransfersEvent.MutationFailed(request.requestId, failure()))
        val action = (failed.state.mutation as TransferMutation.Failed).action as TransferAction.Add
        assertEquals("magnet:?xt=urn:abc", action.submission.value)
        val retainedItems = (failed.state.content as TransfersContent.Ready).items
        assertEquals(listOf(TransferId(2L)), retainedItems.map(TransferItem::id))
    }

    @Test
    fun addSuccessPublishesADurableCompletionToken() {
        val loaded = TransfersReducer.start().complete(TransfersPage(emptyList(), null))
        val adding = TransfersReducer.reduce(loaded, TransfersEvent.Add("https://example.com/file"))
        val request = adding.effect as TransfersEffect.Mutate

        val completed =
            TransfersReducer.reduce(
                adding.state,
                TransfersEvent.MutationSucceeded(request.requestId, item(3L)),
            ).state

        assertEquals(request.requestId, completed.lastSuccessfulAddRequestId)
        assertEquals(TransferMutation.Idle, completed.mutation)
    }

    @Test
    fun cancelRetryAndCleanAreConstrainedByStatus() {
        val rows =
            listOf(
                item(1L),
                item(2L, AppTransferStatus.Failed),
                item(3L, AppTransferStatus.Completed),
                item(4L, AppTransferStatus.Unknown("FUTURE_STATUS")),
            )
        val loaded = TransfersReducer.start().complete(TransfersPage(rows, null))
        val cancel = TransfersReducer.reduce(loaded, TransfersEvent.Cancel(TransferId(1L)))
        assertTrue(cancel.effect is TransfersEffect.Mutate)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Cancel(TransferId(3L))).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Cancel(TransferId(4L))).consumed)
        val retry = TransfersReducer.reduce(loaded, TransfersEvent.RetryTransfer(TransferId(2L)))
        assertTrue(retry.effect is TransfersEffect.Mutate)
        val clean = TransfersReducer.reduce(loaded, TransfersEvent.CleanCompleted)
        assertEquals(TransferAction.Clean, (clean.effect as TransfersEffect.Mutate).action)
    }

    @Test
    fun retryReplacesFirstPageOwnershipWhenTheServerReturnsANewTransfer() {
        val loaded =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(2L, AppTransferStatus.Failed)), TransferCursor("next")),
            )
        val retrying = TransfersReducer.reduce(loaded, TransfersEvent.RetryTransfer(TransferId(2L)))
        val request = retrying.effect as TransfersEffect.Mutate

        val retried =
            TransfersReducer.reduce(
                retrying.state,
                TransfersEvent.MutationSucceeded(request.requestId, item(9L)),
            ).state

        assertEquals(setOf(TransferId(9L)), retried.firstPageIds)
        assertEquals(listOf(TransferId(9L)), (retried.content as TransfersContent.Ready).items.map(TransferItem::id))
    }

    @Test
    fun transferSubmissionsAreValidatedAtTheDomainBoundary() {
        val loaded = TransfersReducer.start().complete(TransfersPage(emptyList(), null))

        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("not a link")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("ftp://example.com/file")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("https://example.com:bad/file")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("https://example.com:+443/file")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("https://example.com:-0/file")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("https://example.com:443:80/file")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("https://example.com:+443:80/file")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("https://example.com:65536/file")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("https://user@@example.com/file")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("https://bad_host/file")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("https://-bad.example/file")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("https://./file")).consumed)
        val overlongHost = List(4) { "a".repeat(63) }.joinToString(".")
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("https://$overlongHost/file")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("magnet:?dn=missing-xt")).consumed)
        assertFalse(TransfersReducer.reduce(loaded, TransfersEvent.Add("magnet:xt=urn:btih:abc")).consumed)
        assertEquals("https://example.com/file", submission("  https://example.com/file  ").value)
        assertEquals("https://example.com:443/file", submission("https://example.com:443/file").value)
        assertEquals("https://user@example.com/file", submission("https://user@example.com/file").value)
        assertEquals("https://user@例え.テスト/file", submission("https://user@例え.テスト/file").value)
        assertEquals("https://[2001:db8::1]:443/file", submission("https://[2001:db8::1]:443/file").value)
        assertEquals("https://example.com./file", submission("https://example.com./file").value)
        assertEquals("https://例え.テスト/file", submission("https://例え.テスト/file").value)
        assertEquals("magnet:?xt=urn:btih:abc", submission("magnet:?xt=urn:btih:abc").value)
    }

    @Test
    fun initialLoadCannotBeOrphanedByAMutation() {
        val start = TransfersReducer.start()

        assertFalse(TransfersReducer.reduce(start.state, TransfersEvent.Add("https://example.com/file")).consumed)
        assertEquals(start.state, TransfersReducer.reduce(start.state, TransfersEvent.CleanCompleted).state)
    }

    @Test
    fun retryCannotStartAReadWhileAMutationIsRunning() {
        val failed =
            TransfersReducer.reduce(
                TransfersReducer.start().state,
                TransfersEvent.ListFailed(TransfersRequestId(1L), failure()),
            ).state
        val adding = TransfersReducer.reduce(failed, TransfersEvent.Add("https://example.com/file"))

        val retry = TransfersReducer.reduce(adding.state, TransfersEvent.RetryLoad)

        assertFalse(retry.consumed)
        assertEquals(adding.state, retry.state)
        assertNull(retry.effect)
    }

    @Test
    fun mutationInvalidatesAnOverlappingRefreshAndCleanRemovesAllLoadedCompletedRows() {
        val rows =
            listOf(
                item(1L, AppTransferStatus.Completed),
                item(2L, AppTransferStatus.Downloading),
                item(3L, AppTransferStatus.Completed),
            )
        val loaded = TransfersReducer.start().complete(TransfersPage(rows, TransferCursor("next")))
        val refreshing = TransfersReducer.reduce(loaded, TransfersEvent.Refresh)
        val refreshRequest = (refreshing.effect as TransfersEffect.Load).requestId
        val cleaning = TransfersReducer.reduce(refreshing.state, TransfersEvent.CleanCompleted)
        val cleanRequest = (cleaning.effect as TransfersEffect.Mutate).requestId

        assertEquals(TransfersRefresh.Idle, cleaning.state.refresh)
        val staleRefresh =
            TransfersReducer.reduce(
                cleaning.state,
                TransfersEvent.ListSucceeded(refreshRequest, TransfersPage(listOf(item(99L)), null)),
            )
        assertFalse(staleRefresh.consumed)

        val cleaned =
            TransfersReducer.reduce(
                cleaning.state,
                TransfersEvent.MutationSucceeded(
                    cleanRequest,
                    affectedIds = setOf(TransferId(1L), TransferId(3L)),
                ),
            ).state
        assertEquals(listOf(TransferId(2L)), (cleaned.content as TransfersContent.Ready).items.map(TransferItem::id))
    }

    @Test
    fun cleanWithoutReturnedIdsRemovesCompletedRowsFromEveryLoadedPage() {
        val first =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(1L, AppTransferStatus.Downloading)), TransferCursor("next")),
            )
        val loading = TransfersReducer.reduce(first, TransfersEvent.LoadNextPage)
        val paginated =
            TransfersReducer.reduce(
                loading.state,
                TransfersEvent.ListSucceeded(
                    (loading.effect as TransfersEffect.Load).requestId,
                    TransfersPage(listOf(item(2L, AppTransferStatus.Completed)), null),
                ),
            ).state
        val cleaning = TransfersReducer.reduce(paginated, TransfersEvent.CleanCompleted)

        val cleaned =
            TransfersReducer.reduce(
                cleaning.state,
                TransfersEvent.MutationSucceeded(
                    (cleaning.effect as TransfersEffect.Mutate).requestId,
                    affectedIds = emptySet(),
                ),
            ).state

        assertEquals(listOf(TransferId(1L)), (cleaned.content as TransfersContent.Ready).items.map(TransferItem::id))
    }

    @Test
    fun removingTheLastLoadedRowReloadsWhenMorePagesExist() {
        val loaded =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(1L)), TransferCursor("next")),
            )
        val cancelling = TransfersReducer.reduce(loaded, TransfersEvent.Cancel(TransferId(1L)))
        val cancelled =
            TransfersReducer.reduce(
                cancelling.state,
                TransfersEvent.MutationSucceeded(
                    (cancelling.effect as TransfersEffect.Mutate).requestId,
                    affectedIds = setOf(TransferId(1L)),
                ),
            )

        assertTrue(cancelled.state.content is TransfersContent.InitialLoading)
        assertNull((cancelled.effect as TransfersEffect.Load).cursor)
        assertEquals(emptySet<TransferCursor>(), cancelled.state.consumedCursors)
    }

    @Test
    fun pollingRefreshesRowsFromEveryLoadedPageInPlace() {
        val first =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(1L)), TransferCursor("next")),
            )
        val loading = TransfersReducer.reduce(first, TransfersEvent.LoadNextPage)
        val paginated =
            TransfersReducer.reduce(
                loading.state,
                TransfersEvent.ListSucceeded(
                    (loading.effect as TransfersEffect.Load).requestId,
                    TransfersPage(listOf(item(2L, AppTransferStatus.Completed)), null),
                ),
            ).state
        val hiddenPoll = TransfersReducer.reduce(paginated, TransfersEvent.Poll)
        assertFalse(hiddenPoll.consumed)
        assertEquals(paginated, hiddenPoll.state)

        val visible =
            TransfersReducer.reduce(paginated, TransfersEvent.VisibilityChanged(true)).state
        val polling = TransfersReducer.reduce(visible, TransfersEvent.Poll)
        val refresh = polling.effect as TransfersEffect.RefreshRows
        assertEquals(listOf(TransferId(1L), TransferId(2L)), refresh.ids)
        assertEquals(TransfersRefresh.Polling(refresh.requestId), polling.state.refresh)

        val refreshed =
            TransfersReducer.reduce(
                polling.state,
                TransfersEvent.RowsRefreshed(
                    refresh.requestId,
                    listOf(
                        item(1L, AppTransferStatus.Completed, TransferFileId(11L)),
                        item(2L, AppTransferStatus.Completed, TransferFileId(12L)),
                    ),
                    emptySet(),
                ),
            ).state

        assertEquals(
            listOf(TransferFileId(11L), TransferFileId(12L)),
            (refreshed.content as TransfersContent.Ready).items.map(TransferItem::fileId),
        )
        assertEquals(TransfersRefresh.Idle, refreshed.refresh)
    }

    @Test
    fun pollingFailureReturnsToIdleWithoutSurfacingForegroundRefreshFailure() {
        val loaded =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(1L)), null),
            ).copy(visible = true)
        val polling = TransfersReducer.reduce(loaded, TransfersEvent.Poll)
        val request = polling.effect as TransfersEffect.RefreshRows

        val failed =
            TransfersReducer.reduce(
                polling.state,
                TransfersEvent.ListFailed(request.requestId, failure()),
            ).state

        assertEquals(TransfersRefresh.Idle, failed.refresh)
        assertEquals(loaded.content, failed.content)
    }

    @Test
    fun pollingAuthenticationFailureRemainsVisibleToSessionRejection() {
        val loaded =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(1L)), null),
            ).copy(visible = true)
        val polling = TransfersReducer.reduce(loaded, TransfersEvent.Poll)
        val request = polling.effect as TransfersEffect.RefreshRows
        val failure = FilesFailure.AuthenticationRequired(PutioConfigurationException("expired"))

        val failed =
            TransfersReducer.reduce(
                polling.state,
                TransfersEvent.ListFailed(request.requestId, failure),
            ).state

        assertEquals(TransfersRefresh.Failed(failure), failed.refresh)
    }

    @Test
    fun foregroundRefreshFailureBlocksPollingUntilRecovery() {
        val loaded =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(1L)), null),
            ).copy(
                visible = true,
                refresh = TransfersRefresh.Failed(failure()),
            )

        val poll = TransfersReducer.reduce(loaded, TransfersEvent.Poll)

        assertFalse(poll.consumed)
        assertEquals(loaded, poll.state)
        assertNull(poll.effect)
        assertTrue(TransfersReducer.reduce(loaded, TransfersEvent.Refresh).consumed)
    }

    @Test
    fun addAfterListFailureReloadsTheAuthoritativeList() {
        val failed =
            TransfersReducer.reduce(
                TransfersReducer.start().state,
                TransfersEvent.ListFailed(TransfersRequestId(1L), failure()),
            ).state
        val adding = TransfersReducer.reduce(failed, TransfersEvent.Add("https://example.com/file"))
        val request = adding.effect as TransfersEffect.Mutate

        val added =
            TransfersReducer.reduce(
                adding.state,
                TransfersEvent.MutationSucceeded(request.requestId, item(3L)),
            )

        assertTrue(added.state.content is TransfersContent.InitialLoading)
        assertEquals(request.requestId, added.state.lastSuccessfulAddRequestId)
        assertNull((added.effect as TransfersEffect.Load).cursor)
    }

    @Test
    fun openStoresDurableNavigationAndNotices() {
        val rows =
            listOf(
                item(1L, AppTransferStatus.Completed, TransferFileId(10L)),
                item(2L, AppTransferStatus.Completed),
                item(3L, AppTransferStatus.Completed, userFileExists = false),
                item(4L, AppTransferStatus.Downloading),
                item(5L, AppTransferStatus.Seeding, TransferFileId(15L), userFileExists = true),
            )
        val state = TransfersReducer.start().complete(TransfersPage(rows, null))

        assertEquals(
            TransferNavigation.Resolving(TransferFileId(10L), TransfersRequestId(2L)),
            state.open(1L).navigation,
        )
        assertEquals(
            TransferNotice.FilePreparing(TransferId(2L), TransfersRequestId(2L)),
            state.open(2L).notice,
        )
        assertEquals(
            TransferNotice.FileUnavailable(TransferId(3L), TransfersRequestId(2L)),
            state.open(3L).notice,
        )
        assertEquals(
            TransferNotice.FilePreparing(TransferId(4L), TransfersRequestId(2L)),
            state.open(4L).notice,
        )
        assertEquals(
            TransferNavigation.Resolving(TransferFileId(15L), TransfersRequestId(2L)),
            state.open(5L).navigation,
        )
    }

    @Test
    fun openInvalidatesBackgroundPollingAndKeepsNavigationEnabled() {
        val loaded =
            TransfersReducer.start().complete(
                TransfersPage(
                    listOf(item(1L, AppTransferStatus.Seeding, TransferFileId(10L))),
                    null,
                ),
            ).copy(visible = true)
        val polling = TransfersReducer.reduce(loaded, TransfersEvent.Poll)
        val pollRequest = (polling.effect as TransfersEffect.RefreshRows).requestId

        val opening = TransfersReducer.reduce(polling.state, TransfersEvent.Open(TransferId(1L)))

        assertTrue(opening.consumed)
        assertEquals(TransfersRefresh.Idle, opening.state.refresh)
        assertEquals(
            TransferNavigation.Resolving(TransferFileId(10L), TransfersRequestId(3L)),
            opening.state.navigation,
        )
        assertFalse(
            TransfersReducer.reduce(
                opening.state,
                TransfersEvent.RowsRefreshed(pollRequest, listOf(item(99L)), emptySet()),
            ).consumed,
        )
    }

    @Test
    fun noticesRequireMatchingAcknowledgementAndAllocateUniqueRequests() {
        val loaded =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(2L, AppTransferStatus.Completed)), null),
            )
        val first = TransfersReducer.reduce(loaded, TransfersEvent.Open(TransferId(2L))).state
        val firstNotice = first.notice as TransferNotice.FilePreparing

        val stale = TransfersReducer.reduce(first, TransfersEvent.DismissNotice(TransfersRequestId(99L)))
        assertFalse(stale.consumed)
        assertEquals(firstNotice, stale.state.notice)

        val second = TransfersReducer.reduce(first, TransfersEvent.Open(TransferId(2L))).state
        val secondNotice = second.notice as TransferNotice.FilePreparing
        assertTrue(secondNotice.requestId != firstNotice.requestId)

        val dismissed = TransfersReducer.reduce(second, TransfersEvent.DismissNotice(secondNotice.requestId)).state
        assertNull(dismissed.notice)
    }

    @Test
    fun openIsRejectedWhileAMutationIsRunning() {
        val loaded =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(1L, AppTransferStatus.Completed, TransferFileId(10L))), null),
            )
        val mutating = TransfersReducer.reduce(loaded, TransfersEvent.CleanCompleted).state

        val open = TransfersReducer.reduce(mutating, TransfersEvent.Open(TransferId(1L)))

        assertFalse(open.consumed)
        assertEquals(mutating, open.state)
        assertNull(open.effect)
    }

    @Test
    fun openResolutionBlocksMutationsUntilTheMatchingCompletion() {
        val loaded =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(1L, AppTransferStatus.Completed, TransferFileId(10L))), null),
            )
        val opening = TransfersReducer.reduce(loaded, TransfersEvent.Open(TransferId(1L)))
        val resolving = opening.state.navigation as TransferNavigation.Resolving

        assertFalse(TransfersReducer.reduce(opening.state, TransfersEvent.CleanCompleted).consumed)
        assertFalse(
            TransfersReducer.reduce(opening.state, TransfersEvent.OpenSucceeded(TransfersRequestId(99L))).consumed,
        )

        val finished = TransfersReducer.reduce(opening.state, TransfersEvent.OpenSucceeded(resolving.requestId)).state
        assertEquals(TransferNavigation.Idle, finished.navigation)
        assertTrue(TransfersReducer.reduce(finished, TransfersEvent.CleanCompleted).consumed)
    }

    @Test
    fun openFailureRemainsDurableUntilDismissed() {
        val loaded =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(1L, AppTransferStatus.Completed, TransferFileId(10L))), null),
            )
        val opening = TransfersReducer.reduce(loaded, TransfersEvent.Open(TransferId(1L))).state
        val resolving = opening.navigation as TransferNavigation.Resolving
        val failure = failure()

        val failed =
            TransfersReducer.reduce(
                opening,
                TransfersEvent.OpenFailed(resolving.requestId, failure),
            ).state
        assertEquals(TransferNavigation.Failed(failure), failed.navigation)

        val dismissed = TransfersReducer.reduce(failed, TransfersEvent.DismissNavigationFailure).state
        assertEquals(TransferNavigation.Idle, dismissed.navigation)
    }

    @Test
    fun openIsRejectedWhileAReadIsInFlight() {
        val loaded =
            TransfersReducer.start().complete(
                TransfersPage(
                    listOf(item(1L, AppTransferStatus.Completed, TransferFileId(10L))),
                    TransferCursor("next"),
                ),
            )
        val refreshing = TransfersReducer.reduce(loaded, TransfersEvent.Refresh).state
        val paging = TransfersReducer.reduce(loaded, TransfersEvent.LoadNextPage).state

        listOf(refreshing, paging).forEach { reading ->
            val open = TransfersReducer.reduce(reading, TransfersEvent.Open(TransferId(1L)))
            assertFalse(open.consumed)
            assertEquals(reading, open.state)
            assertNull(open.effect)
        }
    }

    @Test
    fun pagingRetryDoesNotClearItsFailureWhileAnotherReadIsActive() {
        val failure = failure()
        val loaded =
            TransfersReducer.start().complete(
                TransfersPage(listOf(item(1L)), TransferCursor("next")),
            )
        val failed =
            loaded.copy(
                content =
                    (loaded.content as TransfersContent.Ready).copy(
                        paging = TransfersPaging.Failed(TransferCursor("next"), failure),
                    ),
                refresh = TransfersRefresh.Refreshing(TransfersRequestId(99L)),
            )

        val retry = TransfersReducer.reduce(failed, TransfersEvent.RetryLoad)

        assertFalse(retry.consumed)
        assertEquals(failed, retry.state)
        assertNull(retry.effect)
    }

    private fun TransfersTransition.complete(page: TransfersPage): TransfersState =
        TransfersReducer.reduce(state, TransfersEvent.ListSucceeded(requestId(), page)).state

    private fun TransfersTransition.requestId() = (effect as TransfersEffect.Load).requestId

    private fun TransfersState.open(id: Long) =
        TransfersReducer.reduce(this, TransfersEvent.Open(TransferId(id))).state

    private fun failure() = FilesFailure.Unexpected(IllegalStateException("offline"))

    private fun submission(value: String) = requireNotNull(TransferSubmission.parse(value))

    private fun item(
        id: Long,
        status: AppTransferStatus = AppTransferStatus.Downloading,
        fileId: TransferFileId? = null,
        userFileExists: Boolean? = null,
    ) = TransferItem(
        id = TransferId(id),
        name = "transfer-$id",
        status = status,
        fileId = fileId,
        sizeBytes = 100.0,
        percentDone = 40.0,
        downloadSpeedBytesPerSecond = 2.0,
        uploadSpeedBytesPerSecond = 1.0,
        estimatedSecondsRemaining = 30.0,
        availability = 1.0,
        hasError = status == AppTransferStatus.Failed,
        createdAt = "2026-08-30T00:00:00Z",
        userFileExists = userFileExists,
    )
}
