package io.putdotio.android.files

import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioKnownErrorContract
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.errors.PutioTransportException
import io.putdotio.sdk.files.FilesListResponse
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class SdkFilesRepositoryTest {

    @Test
    fun listsRootThroughSdkAndMapsRawFileData() =
        runBlocking {
            var requestedFolder: Long? = null
            val repository =
                SdkFilesRepository(
                    listFolder = { folderId ->
                        requestedFolder = folderId
                        response(
                            files =
                                listOf(
                                    sdkFile(
                                        id = 4L,
                                        name = "  raw name.mkv  ",
                                        type = PutioFileType.VIDEO,
                                    ),
                                ),
                            cursor = "next",
                        )
                    },
                    continueListing = { error("Unexpected continuation") },
                    getFile = { error("Unexpected file resolution") },
                )

            val result = repository.loadFolder(FilesFolder.Root.id) as FilesRepositoryResult.Success

            assertEquals(0L, requestedFolder)
            assertEquals("  raw name.mkv  ", result.value.items.single().name)
            assertEquals(PutioFileType.VIDEO, result.value.items.single().type)
            assertEquals(FilesCursor("next"), result.value.nextCursor)
        }

    @Test
    fun continuesThroughSdkAndNormalizesBlankCursor() =
        runBlocking {
            var requestedCursor: String? = null
            val repository =
                SdkFilesRepository(
                    listFolder = { error("Unexpected folder load") },
                    continueListing = { cursor ->
                        requestedCursor = cursor
                        response(cursor = "  ")
                    },
                    getFile = { error("Unexpected file resolution") },
                )

            val result = repository.loadNextPage(FilesCursor("opaque-cursor")) as FilesRepositoryResult.Success

            assertEquals("opaque-cursor", requestedCursor)
            assertNull(result.value.nextCursor)
        }

    @Test
    fun classifiesApiAndTransportFailures() =
        runBlocking {
            val unauthorized = repositoryThrowing(apiFailure(statusCode = 401)).loadFolder(FilesFolder.Root.id)
            val unavailable = repositoryThrowing(apiFailure(statusCode = 503)).loadFolder(FilesFolder.Root.id)
            val transport =
                PutioTransportException(
                    request = PutioRequestData("GET", "https://api.put.io/v2/files/list"),
                    cause = IOException("offline"),
                )
            val network = repositoryThrowing(operationFailure(transport)).loadFolder(FilesFolder.Root.id)

            assertTrue((unauthorized as FilesRepositoryResult.Failure).failure is FilesFailure.AuthenticationRequired)
            assertEquals(
                503,
                ((unavailable as FilesRepositoryResult.Failure).failure as FilesFailure.ServerUnavailable).statusCode,
            )
            assertTrue((network as FilesRepositoryResult.Failure).failure is FilesFailure.NetworkUnavailable)
        }

    @Test
    fun resolvesFileDetailsThroughTheFilesBoundary() =
        runBlocking {
            var requestedFileId: Long? = null
            val repository =
                SdkFilesRepository(
                    listFolder = { error("Unexpected folder load") },
                    continueListing = { error("Unexpected continuation") },
                    getFile = { fileId ->
                        requestedFileId = fileId
                        sdkFile(fileId, "movie.mkv", PutioFileType.VIDEO)
                    },
                )

            val result = repository.resolveItem(FilesItemId(42L)) as FilesRepositoryResult.Success

            assertEquals(42L, requestedFileId)
            assertEquals(FilesItemId(42L), result.value.id)
            assertEquals("movie.mkv", result.value.name)
        }

    @Test
    fun retainsOperationContextWhileClassifyingTheUnderlyingFailure() =
        runBlocking {
            val contract = PutioKnownErrorContract(errorType = "invalid_token", statusCode = 401)
            val reason = PutioOperationErrorReason.ErrorType("invalid_token")
            val operationError =
                apiFailure(
                    statusCode = 401,
                    errorType = "invalid_token",
                    contract = contract,
                    reason = reason,
                )

            val result =
                repositoryThrowing(operationError).loadFolder(FilesFolder.Root.id) as FilesRepositoryResult.Failure
            val failure = result.failure as FilesFailure.AuthenticationRequired
            val retainedContext = failure.cause as PutioOperationException

            assertSame(operationError, retainedContext)
            assertEquals("files", retainedContext.domain)
            assertEquals("list", retainedContext.operation)
            assertSame(contract, retainedContext.contract)
            assertSame(reason, retainedContext.reason)
        }

    @Test
    fun classifiesAContractStatusReasonWithoutAnApiUnderlyingError() =
        runBlocking {
            val transport =
                PutioTransportException(
                    request = PutioRequestData("GET", "https://api.put.io/v2/files/list"),
                    cause = IOException("connection reset"),
                )
            val operationError =
                operationFailure(
                    transport,
                    reason = PutioOperationErrorReason.StatusCode(401),
                )

            val result =
                repositoryThrowing(operationError).loadFolder(FilesFolder.Root.id) as FilesRepositoryResult.Failure

            assertTrue(result.failure is FilesFailure.AuthenticationRequired)
            assertSame(operationError, result.failure.cause)
        }

    @Test
    fun boundsUnexpectedExceptionsAsTypedFailures() =
        runBlocking {
            val error = IllegalStateException("broken mapper")
            val result = repositoryThrowing(error).loadFolder(FilesFolder.Root.id) as FilesRepositoryResult.Failure

            assertSame(error, (result.failure as FilesFailure.Unexpected).cause)
        }

    @Test
    fun neverConvertsCancellationIntoAUiFailure() {
        val cancellation = CancellationException("screen closed")
        try {
            runBlocking {
                repositoryThrowing(cancellation).loadFolder(FilesFolder.Root.id)
            }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun repositoryThrowing(error: Throwable): SdkFilesRepository =
        SdkFilesRepository(
            listFolder = { throw error },
            continueListing = { throw error },
            getFile = { throw error },
        )

    private fun apiFailure(
        statusCode: Int,
        errorType: String? = null,
        contract: PutioKnownErrorContract? = null,
        reason: PutioOperationErrorReason? = null,
    ): PutioOperationException {
        val error =
            PutioApiException(
                request = PutioRequestData("GET", "https://api.put.io/v2/files/list"),
                resolvedStatusCode = statusCode,
                resolvedErrorType = errorType,
                envelope = PutioApiErrorEnvelope(statusCode = statusCode, errorType = errorType),
                responseBody = "{}",
                message = "Request rejected",
            )
        return operationFailure(error, contract, reason)
    }

    private fun operationFailure(
        error: io.putdotio.sdk.errors.PutioException,
        contract: PutioKnownErrorContract? = null,
        reason: PutioOperationErrorReason? = null,
    ): PutioOperationException =
        PutioOperationException(
            domain = "files",
            operation = "list",
            contract = contract,
            reason = reason,
            underlyingError = error,
        )

    private fun response(
        files: List<PutioFile> = emptyList(),
        cursor: String? = null,
    ): FilesListResponse =
        FilesListResponse(
            files = files,
            cursor = cursor,
            status = "OK",
        )

    private fun sdkFile(
        id: Long,
        name: String,
        type: PutioFileType,
    ): PutioFile =
        PutioFile(
            id = id,
            parentId = 0L,
            name = name,
            size = 42L,
            createdAt = "2026-08-29T00:00:00Z",
            fileType = type,
        )
}
