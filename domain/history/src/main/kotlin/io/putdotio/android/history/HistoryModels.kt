package io.putdotio.android.history

@JvmInline
public value class HistoryEventId(
    public val value: Long,
)

@JvmInline
public value class HistoryFileId(
    internal val value: Long,
)

@JvmInline
public value class HistoryTransferId(
    internal val value: Long,
)

public sealed interface HistoryEventKind {
    /** `file_shared`; an event without [id] opens nothing but still names the file. */
    public data class File(
        val id: HistoryFileId?,
        val name: String?,
    ) : HistoryEventKind

    /** `transfer_completed`. */
    public data class Transfer(
        val transferId: HistoryTransferId?,
        val fileId: HistoryFileId?,
        val name: String?,
    ) : HistoryEventKind

    /** An event that opens nothing; [subject] is the name its copy states. */
    public data class Notice(
        val type: HistoryNoticeType,
        val subject: String,
    ) : HistoryEventKind

    /** A type with no copy, or an event missing the field its copy names. */
    public data class Other(
        val type: String,
    ) : HistoryEventKind
}

/** The event types iOS gives copy, besides shared files and completed transfers. */
public enum class HistoryNoticeType {
    Upload,
    TransferError,
    RssFileDeleted,
    RssFilterPaused,
    RssTransferError,
    TransferCallbackError,
}

public data class HistoryItem(
    val id: HistoryEventId,
    val createdAt: String,
    val kind: HistoryEventKind,
)

public data class HistoryPage(
    val items: List<HistoryItem>,
    val hasMore: Boolean,
)
