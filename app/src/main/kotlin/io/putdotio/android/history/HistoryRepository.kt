package io.putdotio.android.history

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.toFilesFailure
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.history.HistoryEvent
import io.putdotio.sdk.history.HistoryEventType
import io.putdotio.sdk.history.HistoryListQuery
import io.putdotio.sdk.history.HistoryListResponse
import java.util.concurrent.CancellationException

sealed interface HistoryRepositoryResult<out T> {
    data class Success<T>(val value: T) : HistoryRepositoryResult<T>
    data class Failure(val failure: FilesFailure) : HistoryRepositoryResult<Nothing>
}

interface HistoryRepository {
    suspend fun load(before: HistoryEventId?): HistoryRepositoryResult<HistoryPage>
    suspend fun clear(): HistoryRepositoryResult<Unit>
}

class SdkHistoryRepository internal constructor(
    private val listEvents: suspend (HistoryListQuery) -> HistoryListResponse,
    private val clearEvents: suspend () -> Unit,
) : HistoryRepository {
    constructor(client: PutioClient) : this(
        listEvents = { query -> client.history.list(query) },
        clearEvents = { client.history.clear() },
    )

    override suspend fun load(before: HistoryEventId?): HistoryRepositoryResult<HistoryPage> =
        request {
            val response = listEvents(HistoryListQuery(before = before?.value))
            HistoryPage(response.events.map(HistoryEvent::toHistoryItem), response.hasMore)
        }

    override suspend fun clear(): HistoryRepositoryResult<Unit> = request { clearEvents() }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> request(block: suspend () -> T): HistoryRepositoryResult<T> =
        try {
            HistoryRepositoryResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            HistoryRepositoryResult.Failure(error.toFilesFailure())
        } catch (unexpected: Exception) {
            HistoryRepositoryResult.Failure(FilesFailure.Unexpected(unexpected))
        }
}

private fun HistoryEvent.toHistoryItem(): HistoryItem =
    HistoryItem(
        id = HistoryEventId(id),
        createdAt = createdAt,
        kind = toHistoryEventKind(),
    )

private fun HistoryEvent.toHistoryEventKind(): HistoryEventKind {
    return when (type) {
        HistoryEventType.FILE_SHARED ->
            fileId?.let { HistoryEventKind.File(HistoryFileId(it), fileName) }
                ?: HistoryEventKind.Other(type.raw, fileName)
        HistoryEventType.TRANSFER_COMPLETED ->
            HistoryEventKind.Transfer(
                transferId = transferId?.let(::HistoryTransferId),
                fileId = fileId?.let(::HistoryFileId),
                name = transferName ?: fileName,
            )
        else -> HistoryEventKind.Other(type.raw, rssFilterTitle ?: transferName ?: fileName)
    }
}
