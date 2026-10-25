package io.putdotio.android.files

import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.errors.PutioSerializationException
import io.putdotio.sdk.files.FileDeleteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult

class SdkFilesDeleteRepositoryTest {
    @Test
    fun sendsOnlyPositiveIdsInOneRequestWithExplicitTrashModeAndRetainsSkippedResult() = runBlocking {
        val calls = mutableListOf<Pair<List<Long>, Boolean>>()
        val result = FileDeleteResult(cursor = "skipped-folder", skipped = 1, status = "OK")
        val repository = repository { ids, skipTrash ->
            calls += ids to skipTrash
            result
        }
        for (mode in FilesDeleteMode.entries) {
            val response = repository.delete(listOf(FilesItemId(7L)), mode) as PutioResult.Success
            assertSame(result, response.value)
        }
        repository.delete(listOf(FilesItemId(7L), FilesItemId(8L)), FilesDeleteMode.TRASH)
        assertEquals(listOf(listOf(7L) to false, listOf(7L) to true, listOf(7L, 8L) to false), calls)
        // A zero or negative ID is a bulk selector at the API boundary, and an empty list deletes nothing.
        for (ids in listOf(listOf(0L), listOf(-1L), listOf(7L, 0L), emptyList())) {
            val response = repository.delete(ids.map(::FilesItemId), FilesDeleteMode.TRASH) as PutioResult.Failure
            assertTrue(response.failure.cause is IllegalArgumentException)
        }
        assertEquals(3, calls.size)
    }

    @Test
    fun preservesOperationCausesForAuthAccessLimitsAndInvalidEnvelopes() = runBlocking {
        for (code in listOf(401, 403, 400)) {
            val type = if (code == 400) "FileDeleteChildrenLimitError" else null
            val error = PutioApiException(
                PutioRequestData("POST", "https://api.put.io/v2/files/delete"), code,
                resolvedErrorType = type, envelope = PutioApiErrorEnvelope(errorType = type, statusCode = code),
                responseBody = "{}", message = "Rejected",
            )
            val wrapped = PutioOperationException("files", "delete", null, null, error)
            val failure = (repository { _, _ -> throw wrapped }.delete(
                listOf(FilesItemId(7L)), FilesDeleteMode.TRASH,
            ) as PutioResult.Failure).failure
            assertSame(wrapped, failure.cause)
            when (code) {
                401 -> assertTrue(failure is PutioFailure.AuthenticationRequired)
                403 -> assertTrue(failure is PutioFailure.AccessDenied)
                400 -> assertEquals(type, (failure as PutioFailure.ApiRejected).errorType)
            }
        }
        val invalid = PutioSerializationException(
            PutioRequestData("POST", "https://api.put.io/v2/files/delete"), "{}", IllegalArgumentException("invalid"),
        )
        val wrapped = PutioOperationException("files", "delete", null, null, invalid)
        val failure = (repository { _, _ -> throw wrapped }.delete(
            listOf(FilesItemId(7L)), FilesDeleteMode.TRASH,
        ) as PutioResult.Failure).failure
        assertTrue(failure is PutioFailure.InvalidResponse)
        assertSame(wrapped, failure.cause)
    }

    private fun repository(deleteFile: suspend (List<Long>, Boolean) -> FileDeleteResult) = SdkFilesRepository(
        listFolder = { _, _ -> error("Unexpected folder load") },
        continueListing = { _, _ -> error("Unexpected page") },
        setSort = { _, _ -> error("Unexpected sort") },
        getFile = { error("Unexpected file read") },
        mutations = SdkFilesMutations(
            rename = { _, _ -> error("Unexpected rename") },
            delete = deleteFile,
            move = { _, _ -> error("Unexpected move") },
        ),
    )
}
