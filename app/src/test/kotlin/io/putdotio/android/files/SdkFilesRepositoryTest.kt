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
    fun renamesThroughSdkWithoutChangingTheWholeName() = runBlocking {
        val renamed = mutableListOf<Pair<Long, String>>()
        val repository = SdkFilesRepository(
            listFolder = { _, _ -> error("Unexpected folder load") },
            continueListing = { _, _ -> error("Unexpected continuation") },
            setSort = { _, _ -> error("Unexpected sort") },
            getFile = { error("Unexpected file resolution") },
            renameFile = { id, name -> renamed += id to name },
            deleteFile = { _, _ -> error("Unexpected delete") },
        )
        for (name in listOf("  Türkçe [raw].mkv  ", "", "folder.name")) {
            assertEquals(FilesRepositoryResult.Success(Unit), repository.rename(FilesItemId(42L), name))
        }
        assertEquals(listOf(42L to "  Türkçe [raw].mkv  ", 42L to "", 42L to "folder.name"), renamed)
    }

    @Test
    fun renamePreservesTypedRejectionAndCancellation() = runBlocking {
        for (status in listOf(401, 403)) {
            val error = apiFailure(status)
            val result = repositoryThrowing(error).rename(FilesItemId(42L), "new.mkv") as FilesRepositoryResult.Failure
            if (status == 401) {
                assertTrue(result.failure is FilesFailure.AuthenticationRequired)
            } else {
                assertTrue(result.failure is FilesFailure.AccessDenied)
            }
            assertSame(error, result.failure.cause)
        }
        val cancellation = CancellationException("cancelled rename")
        try {
            repositoryThrowing(cancellation).rename(FilesItemId(42L), "new.mkv")
            fail("Cancellation must propagate")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }

    @Test
    fun listsRootThroughSdkAndMapsRawFileData() =
        runBlocking {
            var requestedFolder: Long? = null
            var requestedPageSize: Int? = null
            val repository =
                SdkFilesRepository(
                    listFolder = { folderId, query ->
                        requestedFolder = folderId
                        requestedPageSize = query.perPage
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
                            parent = sdkFile(
                                id = 0L,
                                name = "Files",
                                type = PutioFileType.FOLDER,
                                sort = "NAME_DESC",
                            ),
                        )
                    },
                    continueListing = { _, _ -> error("Unexpected continuation") },
                    setSort = { _, _ -> error("Unexpected sort") },
                    renameFile = { _, _ -> error("Unexpected rename") },
                    deleteFile = { _, _ -> error("Unexpected delete") },
                    getFile = { error("Unexpected file resolution") },
                )

            val result = repository.loadFolder(FilesFolder.Root.id) as FilesRepositoryResult.Success

            assertEquals(0L, requestedFolder)
            assertEquals(50, requestedPageSize)
            assertEquals("  raw name.mkv  ", result.value.items.single().name)
            assertEquals(PutioFileType.VIDEO, result.value.items.single().type)
            assertEquals(FilesCursor("next"), result.value.nextCursor)
            assertEquals(FilesSort.NAME_DESCENDING, result.value.sort)
        }

    @Test
    fun continuesThroughSdkAndNormalizesBlankCursor() =
        runBlocking {
            var requestedCursor: String? = null
            var requestedPageSize: Int? = null
            val repository =
                SdkFilesRepository(
                    listFolder = { _, _ -> error("Unexpected folder load") },
                    continueListing = { cursor, query ->
                        requestedCursor = cursor
                        requestedPageSize = query.perPage
                        response(cursor = "  ")
                    },
                    setSort = { _, _ -> error("Unexpected sort") },
                    renameFile = { _, _ -> error("Unexpected rename") },
                    deleteFile = { _, _ -> error("Unexpected delete") },
                    getFile = { error("Unexpected file resolution") },
                )

            val result = repository.loadNextPage(FilesCursor("opaque-cursor")) as FilesRepositoryResult.Success

            assertEquals("opaque-cursor", requestedCursor)
            assertEquals(50, requestedPageSize)
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
            val sortFailure =
                repositoryThrowing(operationFailure(transport))
                    .persistSort(FilesFolder.Root.id, FilesSort.NAME_ASCENDING)

            assertTrue((unauthorized as FilesRepositoryResult.Failure).failure is FilesFailure.AuthenticationRequired)
            assertEquals(
                503,
                ((unavailable as FilesRepositoryResult.Failure).failure as FilesFailure.ServerUnavailable).statusCode,
            )
            assertTrue((network as FilesRepositoryResult.Failure).failure is FilesFailure.NetworkUnavailable)
            assertTrue((sortFailure as FilesRepositoryResult.Failure).failure is FilesFailure.NetworkUnavailable)
        }

    @Test
    fun resolvesFileDetailsThroughTheFilesBoundary() =
        runBlocking {
            var requestedFileId: Long? = null
            val repository =
                SdkFilesRepository(
                    listFolder = { _, _ -> error("Unexpected folder load") },
                    continueListing = { _, _ -> error("Unexpected continuation") },
                    setSort = { _, _ -> error("Unexpected sort") },
                    renameFile = { _, _ -> error("Unexpected rename") },
                    deleteFile = { _, _ -> error("Unexpected delete") },
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
    fun retainsHttpStatusWhenAnErrorEnvelopeOverridesIt() = runBlocking {
        val error = apiFailure(statusCode = 404, httpStatusCode = 500)
        val result = repositoryThrowing(error).resolveItem(FilesItemId(7L)) as FilesRepositoryResult.Failure
        val failure = result.failure as FilesFailure.ApiRejected
        assertEquals(404, failure.statusCode)
        assertEquals(500, failure.httpStatusCode)
        assertSame(error, failure.cause)
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

    @Test
    fun neverConvertsSortCancellationIntoAUiFailure() {
        val cancellation = CancellationException("screen closed")
        try {
            runBlocking {
                repositoryThrowing(cancellation)
                    .persistSort(FilesFolder.Root.id, FilesSort.DATE_ADDED_DESCENDING)
            }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun persistsOnlyBoundedSortValuesThroughTheSdk() =
        runBlocking {
            val expected = listOf(
                FilesSort.NAME_ASCENDING to "NAME_ASC",
                FilesSort.NAME_DESCENDING to "NAME_DESC",
                FilesSort.SIZE_ASCENDING to "SIZE_ASC",
                FilesSort.SIZE_DESCENDING to "SIZE_DESC",
                FilesSort.DATE_ADDED_ASCENDING to "DATE_ASC",
                FilesSort.DATE_ADDED_DESCENDING to "DATE_DESC",
                FilesSort.DATE_MODIFIED_ASCENDING to "MODIFIED_ASC",
                FilesSort.DATE_MODIFIED_DESCENDING to "MODIFIED_DESC",
                FilesSort.TYPE_ASCENDING to "TYPE_ASC",
                FilesSort.TYPE_DESCENDING to "TYPE_DESC",
                FilesSort.WATCH_STATUS_ASCENDING to "WATCH_ASC",
                FilesSort.WATCH_STATUS_DESCENDING to "WATCH_DESC",
            )
            val persisted = mutableListOf<Pair<Long, String>>()
            val repository =
                SdkFilesRepository(
                    listFolder = { _, _ -> response() },
                    continueListing = { _, _ -> response() },
                    setSort = { folderId, sort -> persisted += folderId to sort },
                    renameFile = { _, _ -> error("Unexpected rename") },
                    deleteFile = { _, _ -> error("Unexpected delete") },
                    getFile = { error("Unexpected file resolution") },
                )

            assertEquals(12, FilesSort.entries.size)
            expected.forEach { (sort, apiValue) ->
                assertEquals(sort, FilesSort.fromApiValue(apiValue))
                assertTrue(repository.persistSort(FilesItemId(42L), sort) is FilesRepositoryResult.Success)
            }
            assertNull(FilesSort.fromApiValue(null))
            assertNull(FilesSort.fromApiValue("UNKNOWN"))

            assertEquals(expected.map { 42L to it.second }, persisted)
        }

    private fun repositoryThrowing(error: Throwable): SdkFilesRepository =
        SdkFilesRepository(
            listFolder = { _, _ -> throw error },
            continueListing = { _, _ -> throw error },
            setSort = { _, _ -> throw error },
            renameFile = { _, _ -> throw error },
            deleteFile = { _, _ -> throw error },
            getFile = { throw error },
        )

    private fun apiFailure(
        statusCode: Int,
        errorType: String? = null,
        contract: PutioKnownErrorContract? = null,
        reason: PutioOperationErrorReason? = null,
        httpStatusCode: Int = statusCode,
    ): PutioOperationException {
        val error =
            PutioApiException(
                request = PutioRequestData("GET", "https://api.put.io/v2/files/list"),
                resolvedStatusCode = statusCode,
                httpStatusCode = httpStatusCode,
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
        parent: PutioFile? = null,
    ): FilesListResponse =
        FilesListResponse(
            parent = parent,
            files = files,
            cursor = cursor,
            status = "OK",
        )

    private fun sdkFile(
        id: Long,
        name: String,
        type: PutioFileType,
        sort: String? = null,
    ): PutioFile =
        PutioFile(
            id = id,
            parentId = 0L,
            name = name,
            size = 42L,
            createdAt = "2026-08-29T00:00:00Z",
            fileType = type,
            sortBy = sort,
        )
}
