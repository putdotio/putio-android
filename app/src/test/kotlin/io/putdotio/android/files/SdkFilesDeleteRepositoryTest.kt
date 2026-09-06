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
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException

class SdkFilesDeleteRepositoryTest {
    @Test
    fun sendsOnlyOnePositiveIdWithExplicitTrashModeAndRetainsSkippedResult() = runBlocking {
        val calls = mutableListOf<Pair<Long, Boolean>>()
        val result = FileDeleteResult(cursor = "skipped-folder", skipped = 1, status = "OK")
        val repository = repository { id, skipTrash ->
            calls += id to skipTrash
            result
        }
        for (mode in FilesDeleteMode.entries) {
            val response = repository.delete(FilesItemId(7L), mode) as FilesRepositoryResult.Success
            assertSame(result, response.value)
        }
        assertEquals(listOf(7L to false, 7L to true), calls)
        for (id in listOf(0L, -1L)) {
            val response = repository.delete(FilesItemId(id), FilesDeleteMode.TRASH) as FilesRepositoryResult.Failure
            assertTrue(response.failure.cause is IllegalArgumentException)
        }
        assertEquals(2, calls.size)
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
                FilesItemId(7L), FilesDeleteMode.TRASH,
            ) as FilesRepositoryResult.Failure).failure
            assertSame(wrapped, failure.cause)
            when (code) {
                401 -> assertTrue(failure is FilesFailure.AuthenticationRequired)
                403 -> assertTrue(failure is FilesFailure.AccessDenied)
                400 -> assertEquals(type, (failure as FilesFailure.ApiRejected).errorType)
            }
        }
        val invalid = PutioSerializationException(
            PutioRequestData("POST", "https://api.put.io/v2/files/delete"), "{}", IllegalArgumentException("invalid"),
        )
        val wrapped = PutioOperationException("files", "delete", null, null, invalid)
        val failure = (repository { _, _ -> throw wrapped }.delete(
            FilesItemId(7L), FilesDeleteMode.TRASH,
        ) as FilesRepositoryResult.Failure).failure
        assertTrue(failure is FilesFailure.InvalidResponse)
        assertSame(wrapped, failure.cause)
    }

    @Test
    fun preservesCancellationInsteadOfReturningADeleteFailure() = runBlocking {
        val cancellation = CancellationException("closed")
        try {
            repository { _, _ -> throw cancellation }.delete(FilesItemId(7L), FilesDeleteMode.TRASH)
            fail("Cancellation must propagate")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }

    private fun repository(deleteFile: suspend (Long, Boolean) -> FileDeleteResult) = SdkFilesRepository(
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
