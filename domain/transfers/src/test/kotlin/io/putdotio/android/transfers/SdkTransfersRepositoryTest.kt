package io.putdotio.android.transfers

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.transfers.Transfer
import io.putdotio.sdk.files.FileUploadInput
import io.putdotio.sdk.files.FileUploadResult
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.transfers.TransferAddInput
import io.putdotio.sdk.transfers.TransfersAddManyError
import io.putdotio.sdk.transfers.TransfersAddManyResponse
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

        val first = repository.load() as PutioResult.Success
        val second = repository.load(first.value.nextCursor) as PutioResult.Success

        assertEquals(50, listQueries.single().perPage)
        assertEquals("next", continuationQueries.single().first)
        assertEquals(50, continuationQueries.single().second.perPage)
        assertEquals(listOf(TransferId(1L)), second.value.items.map(TransferItem::id))
    }

    @Test
    fun refreshesAFewRowsByIdAndReportsNotFoundRowsAsMissing() = runBlocking {
        val reads = ReadOperations().apply {
            list = { throw AssertionError("A few rows must not list the history") }
            get = { id -> if (id == 4L) throw apiFailure("get", 404) else sdkTransfer(id) }
        }
        val repository = repository(reads = reads)

        val result =
            repository.refresh(
                listOf(TransferId(5L), TransferId(4L), TransferId(3L)),
            ) as PutioResult.Success

        assertEquals(listOf(TransferId(5L), TransferId(3L)), result.value.items.map(TransferItem::id))
        assertEquals(setOf(TransferId(4L)), result.value.missingIds)
    }

    @Test
    fun refreshFailsWhenAByIdReadFailsForAnotherReason() = runBlocking {
        val reads = ReadOperations().apply {
            get = { id -> if (id == 4L) throw apiFailure("get", 500) else sdkTransfer(id) }
        }
        val repository = repository(reads = reads)

        val result = repository.refresh(listOf(TransferId(3L), TransferId(4L))) as PutioResult.Failure

        assertTrue(result.failure is PutioFailure.ServerUnavailable)
    }

    @Test
    fun refreshesManyRowsFromLargePagedReadsAndKeepsRowsAroundMissingTransfers() = runBlocking {
        val queries = mutableListOf<TransfersListQuery>()
        val cursors = mutableListOf<String>()
        val reads = ReadOperations().apply {
            list = { query ->
                queries += query
                response((1L..10L).map(::sdkTransfer), "next")
            }
            continueList = { cursor, query ->
                queries += query
                cursors += cursor
                response(listOf(sdkTransfer(12L)), null)
            }
            get = { throw AssertionError("Many rows must not be read one by one") }
        }
        val repository = repository(reads = reads)

        val result = repository.refresh((1L..12L).map(::TransferId)) as PutioResult.Success

        assertEquals(listOf(1_000, 1_000), queries.map(TransfersListQuery::perPage))
        assertEquals(listOf("next"), cursors)
        assertEquals(((1L..10L) + 12L).map(::TransferId), result.value.items.map(TransferItem::id))
        assertEquals(setOf(TransferId(11L)), result.value.missingIds)
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

        val items = (repository.load() as PutioResult.Success).value.items

        assertEquals(AppTransferStatus.Downloading, items[0].status)
        assertEquals(AppTransferStatus.Completed, items[1].status)
        assertEquals(AppTransferStatus.Failed, items[2].status)
        assertEquals(AppTransferStatus.Unknown("FUTURE_STATUS"), items[3].status)
        assertFalse(items[0].toString().contains(secret))
        val fieldNames = TransferItem::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fieldNames.any { it in setOf("source", "callbackUrl", "links", "raw") })
    }

    @Test
    fun projectionKeepsTheServerFailureReasonAndDropsBlankOnes() = runBlocking {
        val transfers =
            listOf(
                sdkTransfer(1L, TransferStatus.ERROR).copy(errorMessage = "  Downloading text/html is not allowed.\n"),
                sdkTransfer(2L, TransferStatus.ERROR).copy(errorMessage = " "),
                sdkTransfer(3L, TransferStatus.ERROR),
            )
        val reads = ReadOperations().apply { list = { response(transfers, null) } }

        val items = (repository(reads = reads).load() as PutioResult.Success).value.items

        assertEquals(
            listOf("Downloading text/html is not allowed.", null, null),
            items.map(TransferItem::errorMessage),
        )
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

        val items = (repository(reads = reads).load() as PutioResult.Success).value.items

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

        repository.add(TransferAddRequest.Links(requireNotNull(TransferSubmission.parseAll("magnet:?xt=urn:test"))))
        repository.cancel(TransferId(8L))
        repository.retry(TransferId(8L))
        val result = repository.clean(emptyList()) as PutioResult.Success

        assertEquals("magnet:?xt=urn:test", added?.url)
        assertEquals(listOf(8L), cancelled)
        assertEquals(8L, retried)
        assertEquals(emptyList<Long>(), cleaned)
        assertEquals(emptySet<TransferId>(), result.value)
    }

    @Test
    fun severalLinksUseOneMultiAddWithTheDestinationAndReportRefusedLinks() = runBlocking {
        var sent: List<TransferAddInput>? = null
        val repository = repository(mutations = MutationOperations().apply {
            addMany = { inputs ->
                sent = inputs
                TransfersAddManyResponse(
                    errors = listOf(TransfersAddManyError("UNKNOWN_SCHEME", 400, "https://example.invalid/second")),
                    transfers = listOf(sdkTransfer(21L)),
                    status = "OK",
                )
            }
        })
        val links = requireNotNull(TransferSubmission.parseAll("magnet:?xt=urn:first\nhttps://example.invalid/second"))

        val result =
            repository.add(TransferAddRequest.Links(links, saveParentId = 44L)) as PutioResult.Success

        assertEquals(listOf("magnet:?xt=urn:first", "https://example.invalid/second"), sent?.map { it.url })
        assertEquals(listOf(44L, 44L), sent?.map { it.saveParentId })
        assertEquals(listOf(TransferId(21L)), result.value.added.map { it.id })
        assertEquals(listOf("https://example.invalid/second"), result.value.rejectedLinks)
    }

    @Test
    fun singleLinkWithoutDestinationLeavesTheDefaultFolderToPutio() = runBlocking {
        var sent: TransferAddInput? = null
        val repository = repository(mutations = MutationOperations().apply {
            add = {
                sent = it
                sdkTransfer(9L)
            }
        })

        repository.add(TransferAddRequest.Links(requireNotNull(TransferSubmission.parseAll("magnet:?xt=urn:one"))))

        assertEquals(null, sent?.saveParentId)
    }

    @Test
    fun torrentUploadsRequireATorrentAndReturnTheStartedTransfer() = runBlocking {
        var sent: FileUploadInput? = null
        val repository = repository(mutations = MutationOperations().apply {
            upload = {
                sent = it
                FileUploadResult.Transfer(sdkTransfer(31L))
            }
        })
        val torrent = TorrentUpload("Harbor film.torrent", byteArrayOf(0x64, 0x65))

        val result =
            repository.add(TransferAddRequest.Torrent(torrent, saveParentId = 7L)) as PutioResult.Success

        assertEquals(listOf(TransferId(31L)), result.value.added.map { it.id })
        assertEquals("Harbor film.torrent", sent?.fileName)
        assertEquals(7L, sent?.parentId)
        assertEquals(true, sent?.requireTorrent)
        assertEquals("application/x-bittorrent", sent?.mediaType)
        assertSame(torrent.content, sent?.content)
    }

    @Test
    fun torrentStoredAsAFileIsAFailureNotASilentSuccess() = runBlocking {
        val repository = repository(mutations = MutationOperations().apply {
            upload = {
                FileUploadResult.File(
                    PutioFile(id = 3L, name = "a.torrent", createdAt = "", fileType = PutioFileType.FILE),
                )
            }
        })

        val result = repository.add(TransferAddRequest.Torrent(TorrentUpload("a.torrent", byteArrayOf(1))))

        assertTrue((result as PutioResult.Failure).failure is PutioFailure.Unexpected)
    }

    @Test
    fun propagatesAuthenticationFailureAndCancellation() {
        val unauthorized = apiFailure("list", 401, errorType = "invalid_scope")
        val failed = runBlocking { throwingRepository(unauthorized).load() } as PutioResult.Failure
        assertTrue(failed.failure is PutioFailure.AuthenticationRequired)

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
        TransfersReadOperations(reads.list, reads.continueList, reads.get),
        TransfersAddOperations(mutations.add, mutations.addMany, mutations.upload),
        mutations.cancel,
        mutations.retry,
        mutations.clean,
    )

    private inner class ReadOperations {
        var list: suspend (TransfersListQuery) -> TransfersListResponse = { response(emptyList(), null) }
        var continueList: suspend (String, TransfersListQuery) -> TransfersListResponse = { _, _ ->
            response(emptyList(), null)
        }
        var get: suspend (Long) -> Transfer = { sdkTransfer(it) }
    }

    private inner class MutationOperations {
        var add: suspend (TransferAddInput) -> Transfer = { sdkTransfer(1L) }
        var addMany: suspend (List<TransferAddInput>) -> TransfersAddManyResponse = { error("Unexpected addMany") }
        var upload: suspend (FileUploadInput) -> FileUploadResult = { error("Unexpected upload") }
        var cancel: suspend (List<Long>) -> Unit = {}
        var retry: suspend (Long) -> Transfer = { sdkTransfer(it) }
        var clean: suspend (List<Long>) -> TransfersCleanResponse = { TransfersCleanResponse(it, "OK") }
    }

    private fun apiFailure(operation: String, statusCode: Int, errorType: String? = null) =
        PutioOperationException(
            domain = "transfers",
            operation = operation,
            contract = null,
            reason = null,
            underlyingError =
                PutioApiException(
                    request = PutioRequestData("GET", "https://api.put.io/v2/transfers/$operation"),
                    resolvedStatusCode = statusCode,
                    resolvedErrorType = errorType,
                    envelope = PutioApiErrorEnvelope(statusCode = statusCode, errorType = errorType),
                    responseBody = "{}",
                    message = "Rejected",
                ),
        )

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
        errorMessage = null,
        callbackUrl = "https://callback.invalid/secret",
        links = listOf(TransferLink("private", "https://example.invalid/secret")),
    )
}
