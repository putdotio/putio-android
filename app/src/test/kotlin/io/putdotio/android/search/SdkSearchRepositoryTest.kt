package io.putdotio.android.search

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.FileSearchResponse
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SdkSearchRepositoryTest {
    @Test
    fun searchesAndContinuesThroughTypedSdkCalls() =
        runBlocking {
            var requestedKeyword: String? = null
            var requestedCursor: String? = null
            val repository =
                SdkSearchRepository(
                    searchFiles = { query ->
                        requestedKeyword = query.keyword
                        response(
                            files = listOf(file(7L, "  raw result.mkv  ")),
                            cursor = "next",
                            total = 2,
                        )
                    },
                    continueSearch = { cursor ->
                        requestedCursor = cursor
                        response(cursor = "  ", total = 2)
                    },
                )

            val first = repository.search(SearchTerm("result")) as FilesRepositoryResult.Success
            val next = repository.loadNextPage(FilesCursor("next")) as FilesRepositoryResult.Success

            assertEquals("result", requestedKeyword)
            assertEquals("  raw result.mkv  ", first.value.items.single().name)
            assertEquals(FilesCursor("next"), first.value.nextCursor)
            assertEquals(2, first.value.total)
            assertEquals("next", requestedCursor)
            assertNull(next.value.nextCursor)
        }

    @Test
    fun usesTheFilesFailureTaxonomy() =
        runBlocking {
            val error =
                PutioApiException(
                    request = PutioRequestData("GET", "https://api.put.io/v2/files/search"),
                    resolvedStatusCode = 429,
                    resolvedErrorType = null,
                    envelope = PutioApiErrorEnvelope(statusCode = 429),
                    responseBody = "{}",
                    message = "Rate limited",
                )
            val repository = repositoryThrowing(error)

            val result = repository.search(SearchTerm("movie")) as FilesRepositoryResult.Failure

            assertTrue(result.failure is FilesFailure.RateLimited)
            assertSame(error, result.failure.cause)
        }

    @Test
    fun preservesCancellation() {
        val cancellation = CancellationException("query replaced")
        try {
            runBlocking { repositoryThrowing(cancellation).search(SearchTerm("movie")) }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun repositoryThrowing(error: Throwable): SdkSearchRepository =
        SdkSearchRepository(
            searchFiles = { throw error },
            continueSearch = { throw error },
        )

    private fun response(
        files: List<PutioFile> = emptyList(),
        cursor: String? = null,
        total: Int = files.size,
    ): FileSearchResponse = FileSearchResponse(files = files, cursor = cursor, total = total, status = "OK")

    private fun file(
        id: Long,
        name: String,
    ): PutioFile =
        PutioFile(
            id = id,
            parentId = 0L,
            name = name,
            size = 42L,
            createdAt = "2026-08-30T00:00:00Z",
            fileType = PutioFileType.VIDEO,
        )
}
