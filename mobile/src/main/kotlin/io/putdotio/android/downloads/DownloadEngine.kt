package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId

internal interface DownloadEngine {
    suspend fun start(entry: DownloadEntry)

    fun notifyFailed(entry: DownloadEntry, reason: DownloadFailureReason) = Unit

    suspend fun remove(fileId: FilesItemId)

    fun isRemoving(fileId: FilesItemId): Boolean = false

    suspend fun hasRecord(fileId: FilesItemId): Boolean = false

    suspend fun setPaused(fileId: FilesItemId, paused: Boolean)

    fun refreshProgress() = Unit

    val concurrency: Int get() = DOWNLOAD_CONCURRENCY_DEFAULT

    fun setConcurrency(limit: Int) = Unit
}
