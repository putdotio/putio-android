package io.putdotio.android.transfers

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.toFilesFailure
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.transfers.Transfer
import io.putdotio.sdk.files.FileUploadInput
import io.putdotio.sdk.files.FileUploadResult
import io.putdotio.sdk.transfers.TransferAddInput
import io.putdotio.sdk.transfers.TransfersAddManyError
import io.putdotio.sdk.transfers.TransfersAddManyResponse
import io.putdotio.sdk.transfers.TransferStatus
import io.putdotio.sdk.transfers.TransfersCleanResponse
import io.putdotio.sdk.transfers.TransfersListQuery
import io.putdotio.sdk.transfers.TransfersListResponse
import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

interface TransfersRepository {
    suspend fun load(cursor: TransferCursor? = null): FilesRepositoryResult<TransfersPage>
    suspend fun refresh(ids: List<TransferId>): FilesRepositoryResult<TransfersRowRefresh>
    suspend fun add(request: TransferAddRequest): FilesRepositoryResult<TransferAddOutcome>
    suspend fun cancel(id: TransferId): FilesRepositoryResult<Unit>
    suspend fun retry(id: TransferId): FilesRepositoryResult<TransferItem>
    suspend fun clean(ids: List<TransferId>): FilesRepositoryResult<Set<TransferId>>
}

internal class TransfersAddOperations(
    val add: suspend (TransferAddInput) -> Transfer,
    val addMany: suspend (List<TransferAddInput>) -> TransfersAddManyResponse,
    val upload: suspend (FileUploadInput) -> FileUploadResult,
) {
    suspend fun run(request: TransferAddRequest): TransferAddOutcome =
        when (request) {
            is TransferAddRequest.Links -> addLinks(request)
            is TransferAddRequest.Torrent -> addTorrent(request)
        }

    // One link keeps `/transfers/add` so put.io's rejection reaches the user as the failure itself.
    private suspend fun addLinks(request: TransferAddRequest.Links): TransferAddOutcome {
        val inputs = request.links.map { TransferAddInput(url = it.value, saveParentId = request.saveParentId) }
        val single = inputs.singleOrNull()
        if (single != null) return TransferAddOutcome(listOf(add(single).toTransferItem()))
        val response = addMany(inputs)
        return TransferAddOutcome(
            added = response.transfers.map(Transfer::toTransferItem),
            rejectedLinks = response.errors.map(TransfersAddManyError::url).filter(String::isNotBlank).distinct(),
        )
    }

    private suspend fun addTorrent(request: TransferAddRequest.Torrent): TransferAddOutcome {
        val result =
            upload(
                FileUploadInput(
                    content = request.file.content,
                    fileName = request.file.fileName,
                    parentId = request.saveParentId,
                    requireTorrent = true,
                    mediaType = TORRENT_MEDIA_TYPE,
                ),
            )
        val transfer = checkNotNull((result as? FileUploadResult.Transfer)?.transfer) {
            "put.io saved the torrent as a file"
        }
        return TransferAddOutcome(listOf(transfer.toTransferItem()))
    }
}

internal class TransfersReadOperations(
    val list: suspend (TransfersListQuery) -> TransfersListResponse,
    val continueList: suspend (String, TransfersListQuery) -> TransfersListResponse,
    val get: suspend (Long) -> Transfer,
)

class SdkTransfersRepository internal constructor(
    private val reads: TransfersReadOperations,
    private val adds: TransfersAddOperations,
    private val cancelTransfers: suspend (List<Long>) -> Unit,
    private val retryTransfer: suspend (Long) -> Transfer,
    private val cleanTransfers: suspend (List<Long>) -> TransfersCleanResponse,
) : TransfersRepository {
    constructor(client: PutioClient) : this(
        reads =
            TransfersReadOperations(
                list = client.transfers::list,
                continueList = client.transfers::continueList,
                get = client.transfers::get,
            ),
        adds =
            TransfersAddOperations(
                add = client.transfers::add,
                addMany = client.transfers::addMany,
                upload = client.files::upload,
            ),
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
            if (requestedIds.size <= DIRECT_REFRESH_LIMIT) {
                refreshEach(requestedIds)
            } else {
                refreshFromList(requestedIds)
            }
        }
    }

    // `/transfers/list` rebuilds the newest 10,000 ids and returns a full page of rows on
    // every call, so a few polled rows are read by id instead. The limit keeps a 5 s poll
    // well inside the API's 300 requests/minute transfers-get budget.
    private suspend fun refreshEach(ids: List<TransferId>): TransfersRowRefresh =
        coroutineScope {
            val found = ids.map { id -> async { getOrNull(id) } }.awaitAll()
            TransfersRowRefresh(
                items = found.filterNotNull(),
                missingIds = ids.filterIndexed { index, _ -> found[index] == null }.toSet(),
            )
        }

    private suspend fun getOrNull(id: TransferId): TransferItem? =
        try {
            reads.get(id.value).toTransferItem()
        } catch (error: PutioException) {
            if (error.isTransferNotFound()) null else throw error
        }

    private suspend fun refreshFromList(requestedIds: List<TransferId>): TransfersRowRefresh {
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
                .filter { TransferId(it.id) in requestedSet }
                .map(Transfer::toTransferItem)
                .forEach { foundById[it.id] = it }
            cursor =
                response.cursor
                    ?.takeIf(String::isNotBlank)
                    ?.takeIf { foundById.size < requestedSet.size }
            check(cursor == null || consumedCursors.add(cursor)) { "Transfers refresh cursor repeated" }
        } while (cursor != null)
        return TransfersRowRefresh(
            items = requestedIds.mapNotNull(foundById::get),
            missingIds = requestedSet - foundById.keys,
        )
    }

    override suspend fun add(request: TransferAddRequest): FilesRepositoryResult<TransferAddOutcome> =
        request { adds.run(request) }

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
        const val DIRECT_REFRESH_LIMIT = 10
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
        errorMessage = errorMessage?.trim()?.takeIf(String::isNotEmpty),
        createdAt = createdAt,
        userFileExists = userFileExists,
    )

private fun PutioException.isTransferNotFound(): Boolean {
    val failure = toFilesFailure()
    return failure is FilesFailure.ApiRejected &&
        failure.statusCode == HTTP_NOT_FOUND &&
        failure.httpStatusCode == HTTP_NOT_FOUND
}

private const val HTTP_NOT_FOUND = 404
const val TORRENT_MEDIA_TYPE = "application/x-bittorrent"

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
