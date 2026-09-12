package io.putdotio.android.history

import io.putdotio.android.files.FilesFailure
import io.putdotio.sdk.history.HistoryEvent
import io.putdotio.sdk.history.HistoryEventType
import io.putdotio.sdk.history.HistoryListQuery
import io.putdotio.sdk.history.HistoryListResponse
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SdkHistoryRepositoryTest {
    @Test
    fun listsThroughSdkPagesByBeforeAndMapsFileTransferAndOtherEvents() = runBlocking {
        var query: HistoryListQuery? = null
        val repository = SdkHistoryRepository(
            listEvents = {
                query = it
                response(
                    listOf(
                        fileEvent(),
                        transferEvent(),
                        errorEventWithFile(),
                        otherEvent(),
                    ),
                    hasMore = true,
                )
            },
            clearEvents = {},
        )

        val result = repository.load(HistoryEventId(4L)) as HistoryRepositoryResult.Success

        assertEquals(4L, query?.before)
        assertTrue(result.value.hasMore)
        assertEquals(HistoryEventKind.File(HistoryFileId(30L), "movie.mkv"), result.value.items[0].kind)
        assertEquals(
            HistoryEventKind.Transfer(
                transferId = null,
                fileId = HistoryFileId(32L),
                name = "movie.mkv",
            ),
            result.value.items[1].kind,
        )
        assertEquals(
            HistoryEventKind.Other(HistoryEventType.TRANSFER_ERROR.raw, "failed movie"),
            result.value.items[2].kind,
        )
        assertEquals(
            HistoryEventKind.Other(HistoryEventType.RSS_FILTER_PAUSED.raw, "Shows"),
            result.value.items[3].kind,
        )
    }

    @Test
    fun clearUsesSdkBoundary() = runBlocking {
        var clears = 0
        val repository = SdkHistoryRepository({ response(emptyList(), false) }, { clears += 1 })

        assertEquals(HistoryRepositoryResult.Success(Unit), repository.clear())
        assertEquals(1, clears)
    }

    @Test
    fun boundsUnexpectedFailuresAndPreservesCancellation() {
        val unexpected = IllegalStateException("broken")
        val failed = runBlocking { throwingRepository(unexpected).load(null) } as HistoryRepositoryResult.Failure
        assertSame(unexpected, (failed.failure as FilesFailure.Unexpected).cause)

        val cancellation = CancellationException("closed")
        try {
            runBlocking { throwingRepository(cancellation).load(null) }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun throwingRepository(error: Throwable) =
        SdkHistoryRepository(
            listEvents = { throw error },
            clearEvents = { throw error },
        )

    private fun response(events: List<HistoryEvent>, hasMore: Boolean) =
        HistoryListResponse(events = events, hasMore = hasMore, status = "OK")

    private fun fileEvent() = HistoryEvent(
        id = 3L,
        userId = 7L,
        createdAt = "2026-08-30T00:00:00Z",
        type = HistoryEventType.FILE_SHARED,
        fileId = 30L,
        fileName = "movie.mkv",
    )

    private fun transferEvent() = HistoryEvent(
        id = 2L,
        userId = 7L,
        createdAt = "2026-08-30T00:00:00Z",
        type = HistoryEventType.TRANSFER_COMPLETED,
        fileId = 32L,
        fileName = "movie.mkv",
    )

    private fun otherEvent() = HistoryEvent(
        id = 1L,
        userId = 7L,
        createdAt = "2026-08-30T00:00:00Z",
        type = HistoryEventType.RSS_FILTER_PAUSED,
        rssFilterTitle = "Shows",
    )

    private fun errorEventWithFile() = HistoryEvent(
        id = 4L,
        userId = 7L,
        createdAt = "2026-08-30T00:00:00Z",
        type = HistoryEventType.TRANSFER_ERROR,
        fileId = 31L,
        fileName = "failed movie",
    )
}
