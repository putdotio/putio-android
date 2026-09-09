package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId

data class DownloadsState(
    val entries: List<DownloadEntry> = emptyList(),
    /** Bytes held by completed downloads; the cache reports partial progress per row. */
    val storageBytes: Long = 0L,
    val removal: DownloadRemoval? = null,
    /** Rows whose bytes Media3 is still deleting; they take no action until it confirms. */
    val removing: Set<FilesItemId> = emptySet(),
) {
    fun entry(fileId: FilesItemId): DownloadEntry? = entries.firstOrNull { it.fileId == fileId }

    /** True when the row has a complete local copy the player can open offline. */
    fun isAvailableOffline(fileId: FilesItemId): Boolean = entry(fileId)?.isCompleted == true
}

/** A pending "delete local copy" confirmation. */
data class DownloadRemoval(
    val fileId: FilesItemId,
    val name: String,
)

sealed interface DownloadsEvent {
    data class Start(val request: DownloadRequest) : DownloadsEvent

    data class Retry(val fileId: FilesItemId) : DownloadsEvent

    data class RequestRemoval(val fileId: FilesItemId) : DownloadsEvent

    data object CancelRemoval : DownloadsEvent

    data object ConfirmRemoval : DownloadsEvent
}

internal fun DownloadsState.withEntries(entries: List<DownloadEntry>): DownloadsState =
    copy(
        entries = entries.sortedByDescending { it.createdAt },
        storageBytes = entries.sumOf { (it.status as? DownloadStatus.Completed)?.bytes ?: 0L },
        removal = removal?.takeIf { pending -> entries.any { it.fileId == pending.fileId } },
        removing = removing.filterTo(mutableSetOf()) { id -> entries.any { it.fileId == id } },
    )
