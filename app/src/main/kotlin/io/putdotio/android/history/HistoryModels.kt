package io.putdotio.android.history

@JvmInline
value class HistoryEventId(
    val value: Long,
)

@JvmInline
value class HistoryFileId(
    val value: Long,
)

@JvmInline
value class HistoryTransferId(
    val value: Long,
)

sealed interface HistoryEventKind {
    /** `file_shared`. */
    data class File(
        val id: HistoryFileId,
        val name: String?,
    ) : HistoryEventKind

    /** `transfer_completed`. */
    data class Transfer(
        val transferId: HistoryTransferId?,
        val fileId: HistoryFileId?,
        val name: String?,
    ) : HistoryEventKind

    /** An event that opens nothing; [subject] is the name its copy states. */
    data class Notice(
        val type: HistoryNoticeType,
        val subject: String,
    ) : HistoryEventKind

    /** A type with no copy, or an event missing the field its copy names. */
    data class Other(
        val type: String,
    ) : HistoryEventKind
}

/** The event types iOS gives copy, besides shared files and completed transfers. */
enum class HistoryNoticeType {
    Upload,
    TransferError,
    RssFileDeleted,
    RssFilterPaused,
    RssTransferError,
    TransferCallbackError,
}

data class HistoryItem(
    val id: HistoryEventId,
    val createdAt: String,
    val kind: HistoryEventKind,
)

data class HistoryPage(
    val items: List<HistoryItem>,
    val hasMore: Boolean,
)
