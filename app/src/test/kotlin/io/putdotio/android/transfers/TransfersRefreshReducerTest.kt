package io.putdotio.android.transfers

import io.putdotio.android.files.FilesFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransfersRefreshReducerTest {
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
    fun consecutiveFirstPageRefreshesRetainEveryLoadedPageAndReopenPagination() {
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

        val refreshingAgain = TransfersReducer.reduce(refreshed, TransfersEvent.Refresh)
        val secondRequest = refreshingAgain.effect as TransfersEffect.Load
        assertEquals(setOf(TransferId(1L), TransferId(2L)), secondRequest.reconcileIds)
        val refreshedAgain =
            TransfersReducer.reduce(
                refreshingAgain.state,
                TransfersEvent.FirstPageRefreshed(
                    secondRequest.requestId,
                    TransfersPage(listOf(item(1L, AppTransferStatus.Completed)), TransferCursor("one")),
                    reconciledItems = listOf(item(2L)),
                ),
            ).state

        assertEquals(
            listOf(TransferId(1L), TransferId(2L)),
            (refreshedAgain.content as TransfersContent.Ready).items.map(TransferItem::id),
        )
        val loadingRefreshedCursor = TransfersReducer.reduce(refreshedAgain, TransfersEvent.LoadNextPage)
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

    private fun TransfersTransition.complete(page: TransfersPage): TransfersState =
        TransfersReducer.reduce(state, TransfersEvent.ListSucceeded(requestId(), page)).state

    private fun TransfersTransition.requestId() = (effect as TransfersEffect.Load).requestId

    private fun failure() = FilesFailure.Unexpected(IllegalStateException("offline"))

    private fun item(
        id: Long,
        status: AppTransferStatus = AppTransferStatus.Downloading,
    ) = TransferItem(
        id = TransferId(id),
        name = "transfer-$id",
        status = status,
        fileId = null,
        sizeBytes = 100.0,
        percentDone = 40.0,
        downloadSpeedBytesPerSecond = 2.0,
        uploadSpeedBytesPerSecond = 1.0,
        estimatedSecondsRemaining = 30.0,
        availability = 1.0,
        hasError = status == AppTransferStatus.Failed,
        createdAt = "2026-08-30T00:00:00Z",
        userFileExists = null,
    )
}
