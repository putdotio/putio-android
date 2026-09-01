package io.putdotio.android.transfers

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.transfers.Transfer
import io.putdotio.sdk.transfers.TransferAddInput
import io.putdotio.sdk.transfers.TransferLink
import io.putdotio.sdk.transfers.TransferStatus
import io.putdotio.sdk.transfers.TransfersCleanResponse
import io.putdotio.sdk.transfers.TransfersListQuery
import io.putdotio.sdk.transfers.TransfersListResponse
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SdkTransfersRepositoryTest {
    @Test
    fun listsFiftyItemsAndContinuesWithTheCursor() = runBlocking {
        val listQueries = mutableListOf<TransfersListQuery>()
        val continuationQueries = mutableListOf<Pair<String, TransfersListQuery>>()
        val reads = ReadOperations()
        reads.list = {
            listQueries += it
            response(listOf(sdkTransfer(2L)), "next")
        }
        reads.continueList = { cursor, query ->
            continuationQueries += cursor to query
            response(listOf(sdkTransfer(1L)), null)
        }
        val repository = repository(reads = reads)

        val first = repository.load() as FilesRepositoryResult.Success
        val second = repository.load(first.value.nextCursor) as FilesRepositoryResult.Success

        assertEquals(50, listQueries.single().perPage)
        assertEquals("next", continuationQueries.single().first)
        assertEquals(50, continuationQueries.single().second.perPage)
        assertEquals(listOf(TransferId(1L)), second.value.items.map(TransferItem::id))
    }

    @Test
    fun refreshesLoadedRowsWithOneLargePagedRead() = runBlocking {
        val queries = mutableListOf<TransfersListQuery>()
        val reads = ReadOperations().apply {
            list = { query ->
                queries += query
                response((1L..50L).map { sdkTransfer(it, TransferStatus.COMPLETED) }, null)
            }
        }
        val repository = repository(reads = reads)

        val result =
            repository.refresh(listOf(TransferId(3L), TransferId(7L))) as FilesRepositoryResult.Success

        assertEquals(listOf(1_000), queries.map(TransfersListQuery::perPage))
        assertEquals(listOf(TransferId(3L), TransferId(7L)), result.value.items.map(TransferItem::id))
        assertEquals(emptySet<TransferId>(), result.value.missingIds)
    }

    @Test
    fun refreshKeepsSuccessfulRowsAroundMissingTransfers() = runBlocking {
        val cursors = mutableListOf<String>()
        val reads = ReadOperations().apply {
            list = { response(listOf(sdkTransfer(3L)), "next") }
            continueList = { cursor, _ ->
                cursors += cursor
                response(listOf(sdkTransfer(5L)), null)
            }
        }
        val repository = repository(reads = reads)

        val result =
            repository.refresh(
                listOf(TransferId(3L), TransferId(4L), TransferId(5L)),
            ) as FilesRepositoryResult.Success

        assertEquals(listOf("next"), cursors)
        assertEquals(listOf(TransferId(3L), TransferId(5L)), result.value.items.map(TransferItem::id))
        assertEquals(setOf(TransferId(4L)), result.value.missingIds)
    }

    @Test
    fun projectionDropsSensitiveSdkFieldsAndMapsKnownAndUnknownStatuses() = runBlocking {
        val secret = "magnet:?xt=urn:secret"
        val transfers =
            listOf(
                sdkTransfer(1L, TransferStatus.DOWNLOADING, source = secret),
                sdkTransfer(2L, TransferStatus.COMPLETED),
                sdkTransfer(3L, TransferStatus.ERROR),
                sdkTransfer(4L, TransferStatus.fromRaw("FUTURE_STATUS")),
            )
        val reads = ReadOperations().apply { list = { response(transfers, null) } }
        val repository = repository(reads = reads)

        val items = (repository.load() as FilesRepositoryResult.Success).value.items

        assertEquals(AppTransferStatus.Downloading, items[0].status)
        assertEquals(AppTransferStatus.Completed, items[1].status)
        assertEquals(AppTransferStatus.Failed, items[2].status)
        assertEquals(AppTransferStatus.Unknown("FUTURE_STATUS"), items[3].status)
        assertFalse(items[0].toString().contains(secret))
        val fieldNames = TransferItem::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fieldNames.any { it in setOf("source", "callbackUrl", "links", "raw") })
        assertTrue(items[0].hasError)
    }

    @Test
    fun projectionUsesCompletionProgressOnlyWhileFinalizing() = runBlocking {
        val transfers =
            listOf(
                sdkTransfer(
                    id = 1L,
                    status = TransferStatus.COMPLETING,
                    percentDone = 100.0,
                    completionPercent = 25.0,
                ),
                sdkTransfer(
                    id = 2L,
                    status = TransferStatus.STOPPING,
                    percentDone = 100.0,
                    completionPercent = 75.0,
                ),
                sdkTransfer(
                    id = 3L,
                    status = TransferStatus.DOWNLOADING,
                    percentDone = 40.0,
                    completionPercent = 5.0,
                ),
            )
        val reads = ReadOperations().apply { list = { response(transfers, null) } }

        val items = (repository(reads = reads).load() as FilesRepositoryResult.Success).value.items

        assertEquals(listOf(25.0, 75.0, 40.0), items.map(TransferItem::percentDone))
    }

    @Test
    fun addCancelRetryAndCleanUseOnlyTypedInputs() = runBlocking {
        var added: TransferAddInput? = null
        var cancelled: List<Long>? = null
        var retried: Long? = null
        var cleaned: List<Long>? = null
        val mutations = MutationOperations().apply {
            add = {
                added = it
                sdkTransfer(8L)
            }
            cancel = { cancelled = it }
            retry = {
                retried = it
                sdkTransfer(it)
            }
            clean = {
                cleaned = it
                TransfersCleanResponse(it, "OK")
            }
        }
        val repository = repository(mutations = mutations)

        repository.add(requireNotNull(TransferSubmission.parse("magnet:?xt=urn:test")))
        repository.cancel(TransferId(8L))
        repository.retry(TransferId(8L))
        val result = repository.clean(emptyList()) as FilesRepositoryResult.Success

        assertEquals("magnet:?xt=urn:test", added?.url)
        assertEquals(listOf(8L), cancelled)
        assertEquals(8L, retried)
        assertEquals(emptyList<Long>(), cleaned)
        assertEquals(emptySet<TransferId>(), result.value)
    }

    @Test
    fun propagatesAuthenticationFailureAndCancellation() {
        val api =
            PutioApiException(
                request = PutioRequestData("GET", "https://api.put.io/v2/transfers/list"),
                resolvedStatusCode = 401,
                resolvedErrorType = "invalid_scope",
                envelope = PutioApiErrorEnvelope(statusCode = 401, errorType = "invalid_scope"),
                responseBody = "{}",
                message = "Unauthorized",
            )
        val unauthorized =
            PutioOperationException(
                domain = "transfers",
                operation = "list",
                contract = null,
                reason = null,
                underlyingError = api,
            )
        val failed = runBlocking { throwingRepository(unauthorized).load() } as FilesRepositoryResult.Failure
        assertTrue(failed.failure is FilesFailure.AuthenticationRequired)

        val cancellation = CancellationException("closed")
        try {
            runBlocking { throwingRepository(cancellation).load() }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun throwingRepository(error: Throwable): SdkTransfersRepository =
        repository(reads = ReadOperations().apply { list = { throw error } })

    private fun repository(
        reads: ReadOperations = ReadOperations(),
        mutations: MutationOperations = MutationOperations(),
    ) = SdkTransfersRepository(
        TransfersReadOperations(reads.list, reads.continueList),
        mutations.add,
        mutations.cancel,
        mutations.retry,
        mutations.clean,
    )

    private inner class ReadOperations {
        var list: suspend (TransfersListQuery) -> TransfersListResponse = { response(emptyList(), null) }
        var continueList: suspend (String, TransfersListQuery) -> TransfersListResponse = { _, _ ->
            response(emptyList(), null)
        }
    }

    private inner class MutationOperations {
        var add: suspend (TransferAddInput) -> Transfer = { sdkTransfer(1L) }
        var cancel: suspend (List<Long>) -> Unit = {}
        var retry: suspend (Long) -> Transfer = { sdkTransfer(it) }
        var clean: suspend (List<Long>) -> TransfersCleanResponse = { TransfersCleanResponse(it, "OK") }
    }

    private fun response(transfers: List<Transfer>, cursor: String?) =
        TransfersListResponse(cursor = cursor, transfers = transfers, status = "OK")

    private fun sdkTransfer(
        id: Long,
        status: TransferStatus = TransferStatus.DOWNLOADING,
        source: String = "secret-source",
        percentDone: Double? = 42.0,
        completionPercent: Double? = null,
    ) = Transfer(
        id = id,
        name = "transfer-$id",
        source = source,
        status = status,
        fileId = if (status == TransferStatus.COMPLETED) id + 100L else null,
        percentDone = percentDone,
        completionPercent = completionPercent,
        errorMessage = "secret source rejected",
        callbackUrl = "https://callback.invalid/secret",
        links = listOf(TransferLink("private", "https://example.invalid/secret")),
    )
}
