package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId

internal data class DownloadsState(
    val entries: List<DownloadEntry> = emptyList(),
    val storageBytes: Long = 0L,
    val removal: DownloadRemoval? = null,
    val removing: Set<FilesItemId> = emptySet(),
    val concurrency: Int = DOWNLOAD_CONCURRENCY_DEFAULT,
    val focus: FilesItemId? = null,
    val speeds: Map<FilesItemId, Long> = emptyMap(),
) {
    fun entry(fileId: FilesItemId): DownloadEntry? = entries.firstOrNull { it.fileId == fileId }

    fun isAvailableOffline(fileId: FilesItemId): Boolean =
        entry(fileId)?.isCompleted == true && fileId !in removing

    fun rowStatus(fileId: FilesItemId): DownloadStatus? = entry(fileId)?.takeIf { fileId !in removing }?.status

    val queue: List<DownloadEntry>
        get() = entries.filter { it.isActive }.sortedWith(QUEUE_ORDER)

    val needsAttention: List<DownloadEntry>
        get() = entries.filter { it.canRetry }

    val onDevice: List<DownloadEntry>
        get() = entries.filter { it.isCompleted }

    val pauseOrResumeAll: DownloadsEvent?
        get() {
            val rows = entries.filter { it.fileId !in removing }
            val pausable = rows.filter { it.canPause }.mapTo(linkedSetOf()) { it.fileId }
            if (pausable.isNotEmpty()) return DownloadsEvent.Pause(pausable)
            val resumable = rows.filter { it.isPausedByUser }.mapTo(linkedSetOf()) { it.fileId }
            return if (resumable.isEmpty()) null else DownloadsEvent.Resume(resumable)
        }

    fun queuePosition(fileId: FilesItemId): Int? {
        val waiting = queue.filter { it.status == DownloadStatus.Queued && it.fileId !in removing }
        return waiting.indexOfFirst { it.fileId == fileId }.takeIf { it >= 0 }?.plus(1)
    }
}

internal data class DownloadRemoval(
    val fileIds: Set<FilesItemId>,
    val name: String?,
)

internal sealed interface DownloadsEvent {
    data class Start(val request: DownloadRequest) : DownloadsEvent

    data class Retry(val fileId: FilesItemId) : DownloadsEvent

    data class RequestRemoval(val fileIds: Set<FilesItemId>) : DownloadsEvent

    data object CancelRemoval : DownloadsEvent

    data object ConfirmRemoval : DownloadsEvent

    data class Pause(val fileIds: Set<FilesItemId>) : DownloadsEvent

    data class Resume(val fileIds: Set<FilesItemId>) : DownloadsEvent

    data class Cancel(val fileId: FilesItemId) : DownloadsEvent

    data class SetConcurrency(val limit: Int) : DownloadsEvent

    data class LocalCopyMissing(val fileId: FilesItemId) : DownloadsEvent

    data class Focus(val fileId: FilesItemId) : DownloadsEvent

    data object FocusHandled : DownloadsEvent

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

private val QUEUE_ORDER = compareBy<DownloadEntry>({ it.queuedAt }, { it.createdAt }, { it.fileId.value })
