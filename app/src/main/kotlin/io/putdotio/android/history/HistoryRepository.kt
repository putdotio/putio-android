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

private fun HistoryEvent.toHistoryEventKind(): HistoryEventKind =
    when (type) {
        HistoryEventType.FILE_SHARED -> HistoryEventKind.File(fileId?.let(::HistoryFileId), fileName.nonBlank())
        HistoryEventType.TRANSFER_COMPLETED ->
            HistoryEventKind.Transfer(
                transferId = transferId?.let(::HistoryTransferId),
                fileId = fileId?.let(::HistoryFileId),
                name = transferName.nonBlank() ?: fileName.nonBlank(),
            )
        HistoryEventType.UPLOAD -> notice(HistoryNoticeType.Upload, fileName)
        HistoryEventType.TRANSFER_ERROR -> notice(HistoryNoticeType.TransferError, transferName)
        HistoryEventType.FILE_FROM_RSS_DELETED_FOR_SPACE -> notice(HistoryNoticeType.RssFileDeleted, fileName)
        HistoryEventType.RSS_FILTER_PAUSED -> notice(HistoryNoticeType.RssFilterPaused, rssFilterTitle)
        HistoryEventType.TRANSFER_FROM_RSS_ERROR -> notice(HistoryNoticeType.RssTransferError, transferName)
        HistoryEventType.TRANSFER_CALLBACK_ERROR -> notice(HistoryNoticeType.TransferCallbackError, transferName)
        else -> HistoryEventKind.Other(type.raw)
    }

private fun HistoryEvent.notice(type: HistoryNoticeType, subject: String?): HistoryEventKind =
    subject.nonBlank()?.let { HistoryEventKind.Notice(type, it) } ?: HistoryEventKind.Other(this.type.raw)

private fun String?.nonBlank(): String? = this?.takeIf(String::isNotBlank)

/**
 * Only the events [keep] accepts. A page it empties reads on from that page's oldest event,
 * so a run of dropped events neither ends the list nor shows as an empty page.
 */
fun HistoryRepository.keeping(keep: (HistoryEventKind) -> Boolean): HistoryRepository =
    FilteredHistoryRepository(this, keep)

private class FilteredHistoryRepository(
    private val source: HistoryRepository,
    private val keep: (HistoryEventKind) -> Boolean,
) : HistoryRepository {
    override suspend fun load(before: HistoryEventId?): HistoryRepositoryResult<HistoryPage> {
        var cursor = before
        while (true) {
            val page = when (val result = source.load(cursor)) {
                is HistoryRepositoryResult.Success -> result.value
                is HistoryRepositoryResult.Failure -> return result
            }
            val kept = page.items.filter { keep(it.kind) }
            val next = page.items.lastOrNull()?.id?.takeIf { page.hasMore && it.isOlderThan(cursor) }
            if (kept.isNotEmpty() || next == null) {
                return HistoryRepositoryResult.Success(HistoryPage(kept, page.hasMore))
            }
            cursor = next
        }
    }

    override suspend fun clear(): HistoryRepositoryResult<Unit> = source.clear()
}

private fun HistoryEventId.isOlderThan(cursor: HistoryEventId?): Boolean = cursor == null || value < cursor.value
