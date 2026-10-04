package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId

internal data class DownloadsState(
    /** Newest first. */
    val entries: List<DownloadEntry> = emptyList(),
    /** Bytes held by completed downloads; the cache reports partial progress per row. */
    val storageBytes: Long = 0L,
    val removal: DownloadRemoval? = null,
    /** Rows whose bytes Media3 is still deleting; they take no action until it confirms. */
    val removing: Set<FilesItemId> = emptySet(),
    val concurrency: Int = DOWNLOAD_CONCURRENCY_DEFAULT,
    /** A row a notification or link asked to show; the screen opens it once. */
    val focus: FilesItemId? = null,
) {
    fun entry(fileId: FilesItemId): DownloadEntry? = entries.firstOrNull { it.fileId == fileId }

    /** True when the row has a complete local copy the player can open offline and nothing is deleting it. */
    fun isAvailableOffline(fileId: FilesItemId): Boolean =
        entry(fileId)?.isCompleted == true && fileId !in removing

    /** What a Files row shows; a row being deleted shows nothing, as it will once Media3 confirms. */
    fun rowStatus(fileId: FilesItemId): DownloadStatus? = entry(fileId)?.takeIf { fileId !in removing }?.status

    /** Rows that have not finished, in the order Media3 starts them. */
    val queue: List<DownloadEntry>
        get() = entries.filter { it.isActive }.sortedWith(QUEUE_ORDER)

    /** Failed rows and finished rows whose bytes are gone; both offer Download again. */
    val needsAttention: List<DownloadEntry>
        get() = entries.filter { it.canRetry }

    val onDevice: List<DownloadEntry>
        get() = entries.filter { it.isCompleted }

    /** 1 for the next row to start, among rows waiting for a free slot; null for any other row. */
    fun queuePosition(fileId: FilesItemId): Int? {
        val waiting = queue.filter { it.status == DownloadStatus.Queued && it.fileId !in removing }
        return waiting.indexOfFirst { it.fileId == fileId }.takeIf { it >= 0 }?.plus(1)
    }
}

/** A pending "delete local copies" confirmation; it never reaches the put.io originals. */
internal data class DownloadRemoval(
    val fileIds: Set<FilesItemId>,
    /** The row's name when only one is being removed. */
    val name: String?,
)

internal sealed interface DownloadsEvent {
    data class Start(val request: DownloadRequest) : DownloadsEvent

    /** Download again a failed row or one whose local copy is missing. */
    data class Retry(val fileId: FilesItemId) : DownloadsEvent

    data class RequestRemoval(val fileIds: Set<FilesItemId>) : DownloadsEvent

    data object CancelRemoval : DownloadsEvent

    data object ConfirmRemoval : DownloadsEvent

    data class SetConcurrency(val limit: Int) : DownloadsEvent

    /** Offline playback found a completed row's bytes gone. */
    data class LocalCopyMissing(val fileId: FilesItemId) : DownloadsEvent

    data class Focus(val fileId: FilesItemId) : DownloadsEvent

    data object FocusHandled : DownloadsEvent

    /** The Downloads screen started; live progress refreshes until [Hidden]. */
    data object Shown : DownloadsEvent

    data object Hidden : DownloadsEvent
}

internal fun DownloadsState.withEntries(entries: List<DownloadEntry>): DownloadsState {
    val ids = entries.mapTo(mutableSetOf()) { it.fileId }
    return copy(
        entries = entries.sortedByDescending { it.createdAt },
        storageBytes = entries.sumOf { (it.status as? DownloadStatus.Completed)?.bytes ?: 0L },
        removal = removal?.let { pending ->
            val remaining = pending.fileIds.filterTo(mutableSetOf()) { it in ids }
            if (remaining.isEmpty()) null else pending.copy(fileIds = remaining)
        },
        removing = removing.filterTo(mutableSetOf()) { it in ids } +
            entries.filter { it.removing }.map { it.fileId },
    )
}

// Media3 sorts by start time; ties keep the order rows were added.
private val QUEUE_ORDER = compareBy<DownloadEntry>({ it.queuedAt }, { it.createdAt }, { it.fileId.value })
