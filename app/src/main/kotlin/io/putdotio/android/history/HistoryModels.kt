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
    data class File(
        val id: HistoryFileId,
        val name: String?,
    ) : HistoryEventKind

    data class Transfer(
        val transferId: HistoryTransferId?,
        val fileId: HistoryFileId?,
        val name: String?,
    ) : HistoryEventKind

    data class Other(
        val type: String,
        val title: String?,
    ) : HistoryEventKind
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
