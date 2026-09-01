package io.putdotio.android.transfers

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.toFilesFailure
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.transfers.Transfer
import io.putdotio.sdk.transfers.TransferAddInput
import io.putdotio.sdk.transfers.TransferStatus
import io.putdotio.sdk.transfers.TransfersCleanResponse
import io.putdotio.sdk.transfers.TransfersListQuery
import io.putdotio.sdk.transfers.TransfersListResponse
import java.util.concurrent.CancellationException

interface TransfersRepository {
    suspend fun load(cursor: TransferCursor? = null): FilesRepositoryResult<TransfersPage>
    suspend fun refresh(ids: List<TransferId>): FilesRepositoryResult<TransfersRowRefresh>
    suspend fun add(submission: TransferSubmission): FilesRepositoryResult<TransferItem>
    suspend fun cancel(id: TransferId): FilesRepositoryResult<Unit>
    suspend fun retry(id: TransferId): FilesRepositoryResult<TransferItem>
    suspend fun clean(ids: List<TransferId>): FilesRepositoryResult<Set<TransferId>>
}

internal class TransfersReadOperations(
    val list: suspend (TransfersListQuery) -> TransfersListResponse,
    val continueList: suspend (String, TransfersListQuery) -> TransfersListResponse,
)

class SdkTransfersRepository internal constructor(
    private val reads: TransfersReadOperations,
    private val addTransfer: suspend (TransferAddInput) -> Transfer,
    private val cancelTransfers: suspend (List<Long>) -> Unit,
    private val retryTransfer: suspend (Long) -> Transfer,
    private val cleanTransfers: suspend (List<Long>) -> TransfersCleanResponse,
) : TransfersRepository {
    constructor(client: PutioClient) : this(
        reads =
            TransfersReadOperations(
                list = client.transfers::list,
                continueList = client.transfers::continueList,
            ),
        addTransfer = client.transfers::add,
        cancelTransfers = { client.transfers.cancel(it) },
        retryTransfer = client.transfers::retry,
        cleanTransfers = client.transfers::clean,
    )

    override suspend fun load(cursor: TransferCursor?): FilesRepositoryResult<TransfersPage> =
        request {
            val query = TransfersListQuery(perPage = PAGE_SIZE)
            val response =
                if (cursor == null) reads.list(query) else reads.continueList(cursor.value, query)
            response.toTransfersPage()
        }

    override suspend fun refresh(ids: List<TransferId>): FilesRepositoryResult<TransfersRowRefresh> {
        val requestedIds = ids.distinct()
        if (requestedIds.isEmpty()) {
            return FilesRepositoryResult.Success(TransfersRowRefresh(emptyList(), emptySet()))
        }
        return request {
            val requestedSet = requestedIds.toSet()
            val foundById = mutableMapOf<TransferId, TransferItem>()
            val consumedCursors = mutableSetOf<String>()
            val query = TransfersListQuery(perPage = REFRESH_PAGE_SIZE)
            var cursor: String? = null
            do {
                val response =
                    cursor?.let { reads.continueList(it, query) }
                        ?: reads.list(query)
                response.transfers
                    .asSequence()
                    .map(Transfer::toTransferItem)
                    .filter { it.id in requestedSet }
                    .forEach { foundById[it.id] = it }
                cursor =
                    response.cursor
                        ?.takeIf(String::isNotBlank)
                        ?.takeIf { foundById.size < requestedSet.size }
                check(cursor == null || consumedCursors.add(cursor)) { "Transfers refresh cursor repeated" }
            } while (cursor != null)
            TransfersRowRefresh(
                items = requestedIds.mapNotNull(foundById::get),
                missingIds = requestedSet - foundById.keys,
            )
        }
    }

    override suspend fun add(submission: TransferSubmission): FilesRepositoryResult<TransferItem> =
        request { addTransfer(TransferAddInput(url = submission.value)).toTransferItem() }

    override suspend fun cancel(id: TransferId): FilesRepositoryResult<Unit> =
        request { cancelTransfers(listOf(id.value)) }

    override suspend fun retry(id: TransferId): FilesRepositoryResult<TransferItem> =
        request { retryTransfer(id.value).toTransferItem() }

    override suspend fun clean(ids: List<TransferId>): FilesRepositoryResult<Set<TransferId>> =
        request { cleanTransfers(ids.map(TransferId::value)).deletedIds.map(::TransferId).toSet() }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> request(block: suspend () -> T): FilesRepositoryResult<T> =
        try {
            FilesRepositoryResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            FilesRepositoryResult.Failure(error.toFilesFailure())
        } catch (unexpected: Exception) {
            FilesRepositoryResult.Failure(FilesFailure.Unexpected(unexpected))
        }

    private companion object {
        const val PAGE_SIZE = 50
        const val REFRESH_PAGE_SIZE = 1_000
    }
}

internal fun Transfer.toTransferItem(): TransferItem =
    TransferItem(
        id = TransferId(id),
        name = name,
        status = status.toAppStatus(),
        fileId = fileId?.let(::TransferFileId),
        sizeBytes = size,
        percentDone = displayPercentDone(),
        downloadSpeedBytesPerSecond = downSpeed,
        uploadSpeedBytesPerSecond = upSpeed,
        estimatedSecondsRemaining = estimatedTime,
        availability = availability,
        hasError = !errorMessage.isNullOrBlank(),
        createdAt = createdAt,
        userFileExists = userFileExists,
    )

private fun Transfer.displayPercentDone(): Double? =
    when (status) {
        TransferStatus.COMPLETING,
        TransferStatus.STOPPING,
        -> completionPercent ?: percentDone
        else -> percentDone ?: completionPercent
    }

private fun TransfersListResponse.toTransfersPage(): TransfersPage =
    TransfersPage(
        items = transfers.map(Transfer::toTransferItem),
        nextCursor = cursor?.takeIf(String::isNotBlank)?.let(::TransferCursor),
    )

private fun TransferStatus.toAppStatus(): AppTransferStatus =
    when (this) {
        TransferStatus.WAITING -> AppTransferStatus.Waiting
        TransferStatus.PREPARING_DOWNLOAD -> AppTransferStatus.PreparingDownload
        TransferStatus.IN_QUEUE -> AppTransferStatus.Queued
        TransferStatus.DOWNLOADING -> AppTransferStatus.Downloading
        TransferStatus.WAITING_FOR_COMPLETE_QUEUE -> AppTransferStatus.WaitingForCompleteQueue
        TransferStatus.WAITING_FOR_DOWNLOADER -> AppTransferStatus.WaitingForDownloader
        TransferStatus.COMPLETING -> AppTransferStatus.Completing
        TransferStatus.STOPPING -> AppTransferStatus.Stopping
        TransferStatus.SEEDING -> AppTransferStatus.Seeding
        TransferStatus.COMPLETED -> AppTransferStatus.Completed
        TransferStatus.ERROR -> AppTransferStatus.Failed
        TransferStatus.PREPARING_SEED -> AppTransferStatus.PreparingSeed
        else -> AppTransferStatus.Unknown(raw)
    }
