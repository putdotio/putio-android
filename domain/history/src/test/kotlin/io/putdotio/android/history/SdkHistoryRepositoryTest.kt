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
            HistoryEventKind.Notice(HistoryNoticeType.TransferError, "failed movie"),
            result.value.items[2].kind,
        )
        assertEquals(
            HistoryEventKind.Notice(HistoryNoticeType.RssFilterPaused, "Shows"),
            result.value.items[3].kind,
        )
    }

    @Test
    fun eachEventTypeKeepsTheNameItsCopyStatesAndBlankNamesAreAbsent() = runBlocking {
        val events = listOf(
            event(1, HistoryEventType.UPLOAD, fileName = "Harbor film.mp4"),
            event(2, HistoryEventType.FILE_FROM_RSS_DELETED_FOR_SPACE, fileName = "Old episode.mkv"),
            event(3, HistoryEventType.TRANSFER_FROM_RSS_ERROR, transferName = "Feed item"),
            event(4, HistoryEventType.TRANSFER_CALLBACK_ERROR, transferName = "Callback transfer"),
            // The name its copy states is missing: nothing to say but its type.
            event(5, HistoryEventType.TRANSFER_ERROR, fileName = "not the transfer name"),
            event(6, HistoryEventType.VOUCHER, fileName = "unused"),
            event(7, HistoryEventType.fromRaw("brand_new_event"), transferName = "unused"),
            event(8, HistoryEventType.FILE_SHARED, fileName = "no file id"),
            event(9, HistoryEventType.FILE_SHARED, fileName = " "),
            event(10, HistoryEventType.TRANSFER_COMPLETED, fileName = "", transferName = " "),
            event(11, HistoryEventType.UPLOAD, fileName = " "),
        )
        val repository = SdkHistoryRepository({ response(events, hasMore = false) }, {})

        val kinds = (repository.load(null) as HistoryRepositoryResult.Success).value.items.map { it.kind }

        assertEquals(
            listOf(
                HistoryEventKind.Notice(HistoryNoticeType.Upload, "Harbor film.mp4"),
                HistoryEventKind.Notice(HistoryNoticeType.RssFileDeleted, "Old episode.mkv"),
                HistoryEventKind.Notice(HistoryNoticeType.RssTransferError, "Feed item"),
                HistoryEventKind.Notice(HistoryNoticeType.TransferCallbackError, "Callback transfer"),
                HistoryEventKind.Other("transfer_error"),
                HistoryEventKind.Other("voucher"),
                HistoryEventKind.Other("brand_new_event"),
                HistoryEventKind.File(id = null, name = "no file id"),
                HistoryEventKind.File(id = null, name = null),
                HistoryEventKind.Transfer(transferId = null, fileId = null, name = null),
                HistoryEventKind.Other("upload"),
            ),
            kinds,
        )
    }

    @Test
    fun keepingDropsEventsAndReadsPastPagesItEmpties() = runBlocking {
        val shared = { id: Long -> item(id, HistoryEventKind.File(HistoryFileId(id), "Sample $id")) }
        val notice = { id: Long -> item(id, HistoryEventKind.Notice(HistoryNoticeType.TransferError, "Sample $id")) }
        val pages = mapOf(
            null to HistoryPage(listOf(shared(9), notice(8)), hasMore = true),
            HistoryEventId(9) to HistoryPage(listOf(notice(8), notice(7)), hasMore = true),
            HistoryEventId(7) to HistoryPage(listOf(notice(6), shared(5), notice(4)), hasMore = true),
            HistoryEventId(5) to HistoryPage(listOf(notice(4)), hasMore = false),
        )
        val requested = mutableListOf<HistoryEventId?>()
        var clears = 0
        val source = object : HistoryRepository {
            override suspend fun load(before: HistoryEventId?): HistoryRepositoryResult<HistoryPage> {
                requested += before
                return HistoryRepositoryResult.Success(pages.getValue(before))
            }

            override suspend fun clear(): HistoryRepositoryResult<Unit> {
                clears += 1
                return HistoryRepositoryResult.Success(Unit)
            }
        }
        val repository = source.keeping { it is HistoryEventKind.File }

        assertEquals(
            HistoryRepositoryResult.Success(HistoryPage(listOf(shared(9)), hasMore = true)),
            repository.load(null),
        )
        assertEquals(
            HistoryRepositoryResult.Success(HistoryPage(listOf(shared(5)), hasMore = true)),
            repository.load(HistoryEventId(9)),
        )
        assertEquals(
            HistoryRepositoryResult.Success(HistoryPage(emptyList(), hasMore = false)),
            repository.load(HistoryEventId(5)),
        )
        assertEquals(listOf(null, HistoryEventId(9), HistoryEventId(7), HistoryEventId(5)), requested)
        assertEquals(HistoryRepositoryResult.Success(Unit), repository.clear())
        assertEquals(1, clears)
    }

    @Test
    fun keepingStopsWhenAPageDoesNotMoveOlderAndPassesFailuresThrough() = runBlocking {
        val stuck = object : HistoryRepository {
            override suspend fun load(before: HistoryEventId?) = HistoryRepositoryResult.Success(
                HistoryPage(listOf(item(3, HistoryEventKind.Other("voucher"))), hasMore = true),
            )

            override suspend fun clear() = HistoryRepositoryResult.Success(Unit)
        }
        assertEquals(
            HistoryRepositoryResult.Success(HistoryPage(emptyList(), hasMore = true)),
            stuck.keeping { false }.load(HistoryEventId(3)),
        )

        val failure = HistoryRepositoryResult.Failure(FilesFailure.Unexpected(IllegalStateException("down")))
        val failing = object : HistoryRepository {
            override suspend fun load(before: HistoryEventId?) = failure

            override suspend fun clear() = HistoryRepositoryResult.Success(Unit)
        }
        assertSame(failure, failing.keeping { true }.load(null))
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
        transferName = "failed movie",
    )

    private fun event(
        id: Long,
        type: HistoryEventType,
        fileName: String? = null,
        transferName: String? = null,
    ) = HistoryEvent(
        id = id,
        userId = 7L,
        createdAt = "2026-08-30T00:00:00Z",
        type = type,
        fileName = fileName,
        transferName = transferName,
    )

    private fun item(id: Long, kind: HistoryEventKind) = HistoryItem(HistoryEventId(id), "2026-08-30T00:00:00Z", kind)
}
