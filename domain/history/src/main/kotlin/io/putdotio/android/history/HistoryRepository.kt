package io.putdotio.android.history

import io.putdotio.android.PutioResult
import io.putdotio.android.putioRequest
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.history.HistoryEvent
import io.putdotio.sdk.history.HistoryEventType
import io.putdotio.sdk.history.HistoryListQuery
import io.putdotio.sdk.history.HistoryListResponse

public interface HistoryRepository {
    public suspend fun load(before: HistoryEventId?): PutioResult<HistoryPage>
    public suspend fun clear(): PutioResult<Unit>
}

public class SdkHistoryRepository internal constructor(
    private val listEvents: suspend (HistoryListQuery) -> HistoryListResponse,
    private val clearEvents: suspend () -> Unit,
) : HistoryRepository {
    public constructor(client: PutioClient) : this(
        listEvents = { query -> client.history.list(query) },
        clearEvents = { client.history.clear() },
    )

    override suspend fun load(before: HistoryEventId?): PutioResult<HistoryPage> =
        putioRequest {
            val response = listEvents(HistoryListQuery(before = before?.value))
            HistoryPage(response.events.map(HistoryEvent::toHistoryItem), response.hasMore)
        }

    override suspend fun clear(): PutioResult<Unit> = putioRequest { clearEvents() }
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
public fun HistoryRepository.keeping(keep: (HistoryEventKind) -> Boolean): HistoryRepository =
    FilteredHistoryRepository(this, keep)

private class FilteredHistoryRepository(
    private val source: HistoryRepository,
    private val keep: (HistoryEventKind) -> Boolean,
) : HistoryRepository {
    override suspend fun load(before: HistoryEventId?): PutioResult<HistoryPage> {
        var cursor = before
        while (true) {
            val page = when (val result = source.load(cursor)) {
                is PutioResult.Success -> result.value
                is PutioResult.Failure -> return result
            }
            val kept = page.items.filter { keep(it.kind) }
            val next = page.items.lastOrNull()?.id?.takeIf { page.hasMore && it.isOlderThan(cursor) }
            if (kept.isNotEmpty() || next == null) {
                return PutioResult.Success(HistoryPage(kept, page.hasMore))
            }
            cursor = next
        }
    }

    override suspend fun clear(): PutioResult<Unit> = source.clear()
}

private fun HistoryEventId.isOlderThan(cursor: HistoryEventId?): Boolean = cursor == null || value < cursor.value
