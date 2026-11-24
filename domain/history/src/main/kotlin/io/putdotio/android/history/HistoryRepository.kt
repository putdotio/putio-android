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
            val response = listEvents(HistoryListQuery(perPage = HISTORY_PAGE_SIZE, before = before?.value))
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
        HistoryEventType.FILE_SHARED -> HistoryEventKind.File(
            id = fileId?.let(::HistoryFileId),
            name = fileName.nonBlank(),
            sharedBy = sharingUserName.nonBlank(),
            fileSizeBytes = fileSize.nonNegative(),
        )
        HistoryEventType.TRANSFER_COMPLETED ->
            HistoryEventKind.Transfer(
                transferId = transferId?.let(::HistoryTransferId),
                fileId = fileId?.let(::HistoryFileId),
                name = transferName.nonBlank() ?: fileName.nonBlank(),
                transferSizeBytes = transferSize.nonNegative(),
            )
        HistoryEventType.UPLOAD -> notice(
            type = HistoryNoticeType.Upload,
            subject = fileName,
            fileId = fileId?.let(::HistoryFileId),
            fileSizeBytes = fileSize.nonNegative(),
        )
        HistoryEventType.TRANSFER_ERROR -> notice(HistoryNoticeType.TransferError, transferName)
        HistoryEventType.FILE_FROM_RSS_DELETED_FOR_SPACE -> notice(
            type = HistoryNoticeType.RssFileDeleted,
            subject = fileName,
            fileSizeBytes = fileSize.nonNegative(),
        )
        HistoryEventType.RSS_FILTER_PAUSED -> notice(HistoryNoticeType.RssFilterPaused, rssFilterTitle)
        HistoryEventType.TRANSFER_FROM_RSS_ERROR -> notice(HistoryNoticeType.RssTransferError, transferName)
        HistoryEventType.TRANSFER_CALLBACK_ERROR -> notice(HistoryNoticeType.TransferCallbackError, transferName)
        else -> HistoryEventKind.Other(type.raw)
    }

private fun HistoryEvent.notice(
    type: HistoryNoticeType,
    subject: String?,
    fileId: HistoryFileId? = null,
    fileSizeBytes: Long? = null,
): HistoryEventKind =
    subject.nonBlank()?.let { HistoryEventKind.Notice(type, it, fileId, fileSizeBytes) }
        ?: HistoryEventKind.Other(this.type.raw)

private fun String?.nonBlank(): String? = this?.takeIf(String::isNotBlank)

private fun Long?.nonNegative(): Long? = this?.takeIf { it >= 0L }

private const val HISTORY_PAGE_SIZE = 50
