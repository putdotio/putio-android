package io.putdotio.android.trash

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.sdk.OkResponse
import io.putdotio.sdk.files.FileDetailsQuery
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.trash.TrashBulkInput
import io.putdotio.sdk.trash.TrashContinueQuery
import io.putdotio.sdk.trash.TrashFile
import io.putdotio.sdk.trash.TrashListQuery
import io.putdotio.sdk.trash.TrashListResponse
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkTrashRepositoryTest {
    @Test
    fun listingUsesBoundedPagesAndPreservesMetadataWithoutInventingContinuationTotals() = runBlocking {
        var firstQuery: TrashListQuery? = null
        var nextQuery: TrashContinueQuery? = null
        val repository = repository(
            list = { query -> firstQuery = query; response() },
            continueList = { cursor, query ->
                assertEquals("next", cursor)
                nextQuery = query
                response().copy(total = null, trashSize = 0L, cursor = null)
            },
        )
        val first = (repository.load() as FilesRepositoryResult.Success).value
        val next = (repository.loadNextPage(FilesCursor("next")) as FilesRepositoryResult.Success).value
        assertEquals(50, firstQuery?.perPage)
        assertEquals(50, nextQuery?.perPage)
        assertEquals(" raw Türkçe.txt ", first.items.single().name)
        assertEquals("2026-09-06", first.items.single().deletedAt)
        assertEquals("2026-09-20", first.items.single().expirationDate)
        assertEquals(20, first.total)
        assertEquals(123L, first.trashSizeBytes)
        assertNull(next.total)
        assertNull(next.trashSizeBytes)
    }

    @Test
    fun restoreSendsOnePositiveIdAndLookupRequestsNoMediaUrls() = runBlocking {
        val inputs = mutableListOf<TrashBulkInput>()
        var detailsQuery: FileDetailsQuery? = null
        val repository = repository(
            restore = { inputs += it; OkResponse("OK") },
            getFile = { id, query ->
                assertEquals(7L, id)
                detailsQuery = query
                PutioFile(
                    id = id, name = "restored", fileType = PutioFileType.TEXT, size = 12L,
                    createdAt = "2026-09-01", parentId = 0L,
                )
            },
        )
        assertEquals(FilesRepositoryResult.Success(Unit), repository.restore(FilesItemId(7L)))
        for (invalid in listOf(0L, -1L)) {
            assertTrue(repository.restore(FilesItemId(invalid)) is FilesRepositoryResult.Failure)
        }
        assertEquals(listOf(TrashBulkInput(ids = listOf(7L))), inputs)
        val resolved = (repository.resolveItem(FilesItemId(7L)) as FilesRepositoryResult.Success).value
        assertEquals(FilesItemId(0L), resolved.parentId)
        assertEquals(FileDetailsQuery(false, false, false, false), detailsQuery)
    }

    @Test
    fun sdkErrorsKeepTypedCausesAndCancellationAcrossAllBoundaries() = runBlocking {
        val cause = apiFailure(401, "invalid_token").cause
        val repository = repository(list = { throw cause }, restore = { throw cause })
        val failure = repository.load() as FilesRepositoryResult.Failure
        assertTrue(failure.failure is FilesFailure.AuthenticationRequired)
        assertSame(cause, failure.failure.cause)
        val cancellation = CancellationException("cancel session")
        val cancelled = repository(
            list = { throw cancellation }, continueList = { _, _ -> throw cancellation },
            restore = { throw cancellation }, getFile = { _, _ -> throw cancellation },
        )
        val operations: List<suspend () -> Any> = listOf(
            { cancelled.load() }, { cancelled.loadNextPage(FilesCursor("next")) },
            { cancelled.restore(FilesItemId(7L)) }, { cancelled.resolveItem(FilesItemId(7L)) },
        )
        for (operation in operations) {
            try {
                operation()
                error("Expected cancellation")
            } catch (caught: CancellationException) {
                assertSame(cancellation, caught)
            }
        }
    }

    @Test
    fun invalidTrashIdsAndSizesFailInsteadOfBecomingActions() = runBlocking {
        for (file in listOf(response().files.single().copy(id = 0L), response().files.single().copy(size = -1L))) {
            val repository = repository(list = { response().copy(files = listOf(file)) })
            val result = repository.load() as FilesRepositoryResult.Failure
            assertTrue(result.failure is FilesFailure.InvalidResponse)
        }
    }


    private class Endpoints(
        val list: suspend (TrashListQuery) -> TrashListResponse = { response() },
        val continueList: suspend (String, TrashContinueQuery) -> TrashListResponse = { _, _ -> response() },
        val restore: suspend (TrashBulkInput) -> OkResponse = { OkResponse("OK") },
        val getFile: suspend (Long, FileDetailsQuery) -> PutioFile = { _, _ -> error("Unexpected lookup") },
        val delete: suspend (TrashBulkInput) -> OkResponse = { OkResponse("OK") },
        val empty: suspend () -> OkResponse = { OkResponse("OK") },
    )

    private fun repository(
        list: suspend (TrashListQuery) -> TrashListResponse = { response() },
        continueList: suspend (String, TrashContinueQuery) -> TrashListResponse = { _, _ -> response() },
        restore: suspend (TrashBulkInput) -> OkResponse = { OkResponse("OK") },
        getFile: suspend (Long, FileDetailsQuery) -> PutioFile = { _, _ -> error("Unexpected lookup") },
    ) = repository(Endpoints(list, continueList, restore, getFile))

    private fun repository(endpoints: Endpoints) = SdkTrashRepository(
        endpoints.list, endpoints.continueList, endpoints.restore, endpoints.getFile, endpoints.delete, endpoints.empty,
    )

    @Test
    fun bulkActionsSendExactSelectionsAndRejectInvalidIds() = runBlocking {
        val deletes = mutableListOf<TrashBulkInput>()
        val restores = mutableListOf<TrashBulkInput>()
        var empties = 0
        val repository = repository(Endpoints(
            restore = { restores += it; OkResponse("OK") },
            delete = { deletes += it; OkResponse("OK") },
            empty = { empties += 1; OkResponse("OK") },
        ))
        assertEquals(FilesRepositoryResult.Success(Unit), repository.deleteItem(FilesItemId(7L)))
        assertTrue(repository.deleteItem(FilesItemId(0L)) is FilesRepositoryResult.Failure)
        assertEquals(listOf(TrashBulkInput(ids = listOf(7L))), deletes)
        assertEquals(FilesRepositoryResult.Success(Unit),
            repository.restoreAll(TrashBulkSelection(FilesCursor("snapshot"), listOf(FilesItemId(7L)))))
        assertEquals(FilesRepositoryResult.Success(Unit),
            repository.restoreAll(TrashBulkSelection(null, listOf(FilesItemId(7L), FilesItemId(9L)))))
        val invalid = repository.restoreAll(TrashBulkSelection(null, listOf(FilesItemId(-1L))))
        assertTrue(invalid is FilesRepositoryResult.Failure)
        assertEquals(listOf(TrashBulkInput(cursor = "snapshot"), TrashBulkInput(ids = listOf(7L, 9L))), restores)
        assertEquals(FilesRepositoryResult.Success(Unit), repository.empty())
        assertEquals(1, empties)
        val cause = apiFailure(401, "invalid_token").cause
        val failing = repository(Endpoints(delete = { throw cause }, empty = { throw cause }))
        val deleteFailure = (failing.deleteItem(FilesItemId(7L)) as FilesRepositoryResult.Failure).failure
        assertTrue(deleteFailure is FilesFailure.AuthenticationRequired)
        assertTrue((failing.empty() as FilesRepositoryResult.Failure).failure is FilesFailure.AuthenticationRequired)
    }
}

private fun response() = TrashListResponse(
    files = listOf(TrashFile(
        id = 7L, name = " raw Türkçe.txt ", size = 12L, parentId = 2L,
        fileType = PutioFileType.TEXT, createdAt = "2026-09-01", deletedAt = "2026-09-06",
        expirationDate = "2026-09-20",
    )), total = 20, trashSize = 123L, cursor = "next", status = "OK",
)
