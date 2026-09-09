package io.putdotio.android.downloads

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import io.putdotio.android.files.FilesItemId
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Bridges one user's download index to the process-wide Media3 manager.
 * Requests carry the token-free API URL; Media3 persists them, resumes them, and
 * reports progress back here. Requests of any other user are stopped while this
 * user is signed in, so no session ever drives another account's transfers.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MobileDownloadEngine(
    context: Context,
    private val store: MobileDownloadStore,
    private val userId: Long,
    private val downloads: MobileDownloadCache = MobileDownloadCache.get(context),
    private val downloadManager: DownloadManager = downloads.downloadManager,
) : DownloadEngine, DownloadManager.Listener {
    private val appContext = context.applicationContext
    private val removing = mutableSetOf<FilesItemId>()

    init {
        downloads.activeUserId = userId
        downloadManager.addListener(this)
        reconcile()
    }

    /** Media3's index is the source of truth for terminal states reached while no UI listened. */
    private fun reconcile() {
        val known = mutableSetOf<FilesItemId>()
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                val owner = download.request.ownerUserId()
                val fileId = fileIdOf(download.request.id)
                if (fileId == null) {
                    // Another account's request: park it; its owner's session resumes it.
                    if (owner != null && !download.isTerminalState) {
                        downloadManager.setStopReason(download.request.id, STOP_REASON_OTHER_USER)
                    }
                    continue
                }
                known += fileId
                if (download.state == Download.STATE_REMOVING) synchronized(removing) { removing += fileId }
                if (download.stopReason == STOP_REASON_OTHER_USER) {
                    downloadManager.setStopReason(download.request.id, Download.STOP_REASON_NONE)
                }
                reflect(download, null)
            }
        }
        // Media3 is the source of truth for bytes: a row it does not know either never
        // reached the service (re-issue a fresh Queued row) or was removed while nothing
        // listened (drop it, its bytes are gone).
        for (entry in store.entries.value) {
            if (entry.fileId in known) continue
            if (entry.status == DownloadStatus.Queued) start(entry) else store.removeBlocking(entry.fileId)
        }
        downloadManager.resumeDownloads()
    }

    override fun start(entry: DownloadEntry) {
        val request = DownloadRequest.Builder(contentId(entry.fileId), entry.artifact.apiUrl(entry.fileId).toUri())
            .setMimeType(if (entry.artifact == DownloadArtifact.HLS) MimeTypes.APPLICATION_M3U8 else null)
            .build()
        synchronized(removing) { removing -= entry.fileId }
        DownloadService.sendAddDownload(appContext, MobileDownloadService::class.java, request, true)
    }

    override fun remove(fileId: FilesItemId) {
        val known = runCatching { downloadManager.downloadIndex.getDownload(contentId(fileId)) }.getOrNull()
        if (known == null) {
            // Media3 never accepted this row, so there are no bytes to wait for.
            store.removeBlocking(fileId)
            return
        }
        synchronized(removing) { removing += fileId }
        DownloadService.sendRemoveDownload(appContext, MobileDownloadService::class.java, contentId(fileId), false)
    }

    /** True while Media3 is still deleting this file's bytes; a new download must wait. */
    override fun isRemoving(fileId: FilesItemId): Boolean = synchronized(removing) { fileId in removing }

    fun close() {
        downloadManager.removeListener(this)
        if (downloads.activeUserId == userId) downloads.activeUserId = null
        // Leave this user's transfers parked until the next session that owns them.
        for (download in downloadManager.currentDownloads) {
            if (fileIdOf(download.request.id) != null) {
                downloadManager.setStopReason(download.request.id, STOP_REASON_OTHER_USER)
            }
        }
    }

    override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
        reflect(download, finalException)
    }

    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
        val fileId = fileIdOf(download.request.id) ?: return
        synchronized(removing) { removing -= fileId }
        store.removeBlocking(fileId)
    }

    // Media3 keeps queued rows queued while offline; the rows show why nothing moves.
    override fun onWaitingForRequirementsChanged(downloadManager: DownloadManager, waitingForRequirements: Boolean) {
        for (download in downloadManager.currentDownloads) reflect(download, null)
    }

    private fun reflect(download: Download, error: Exception?) {
        val fileId = fileIdOf(download.request.id) ?: return
        if (download.state == Download.STATE_REMOVING) return
        val status = download.toStatus(error, downloadManager.isWaitingForRequirements) ?: return
        store.updateStatusBlocking(fileId) { it.copy(status = status) }
    }

    private fun contentId(fileId: FilesItemId): String = "$userId:${fileId.value}"

    private fun fileIdOf(contentId: String): FilesItemId? {
        val (owner, file) = contentId.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        if (owner.toLongOrNull() != userId) return null
        return file.toLongOrNull()?.takeIf { it > 0L }?.let(::FilesItemId)
    }

    private companion object {
        /** Media3 stop reason for requests whose owner is not the signed-in user. */
        const val STOP_REASON_OTHER_USER = 1
    }
}

internal fun DownloadArtifact.apiUrl(fileId: FilesItemId): String =
    when (this) {
        DownloadArtifact.HLS -> "https://api.put.io/v2/files/${fileId.value}/hls/media.m3u8?subtitle_key=all"
        DownloadArtifact.ORIGINAL -> "https://api.put.io/v2/files/${fileId.value}/stream"
    }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun Download.toStatus(error: Exception?, waitingForNetwork: Boolean): DownloadStatus? =
    when (state) {
        Download.STATE_QUEUED, Download.STATE_RESTARTING ->
            if (waitingForNetwork) DownloadStatus.WaitingForNetwork(bytesDownloaded) else DownloadStatus.Queued
        Download.STATE_DOWNLOADING -> DownloadStatus.Downloading(
            bytesDownloaded,
            contentLength.takeIf { it > 0L },
        )
        Download.STATE_COMPLETED -> DownloadStatus.Completed(bytesDownloaded)
        Download.STATE_FAILED -> DownloadStatus.Failed(error.toFailureReason(), bytesDownloaded)
        // Stopped by sign-out or a missing network; both resume without user action.
        Download.STATE_STOPPED -> DownloadStatus.WaitingForNetwork(bytesDownloaded)
        else -> null
    }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun Exception?.toFailureReason(): DownloadFailureReason {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is HttpDataSource.InvalidResponseCodeException -> return when (current.responseCode) {
                HTTP_UNAUTHORIZED -> DownloadFailureReason.AUTHENTICATION
                HTTP_FORBIDDEN, HTTP_NOT_FOUND, HTTP_GONE -> DownloadFailureReason.UNAVAILABLE
                else -> DownloadFailureReason.UNEXPECTED
            }
            is UnknownHostException, is SocketException, is TimeoutException -> return DownloadFailureReason.NETWORK
            is HttpDataSource.HttpDataSourceException -> return DownloadFailureReason.NETWORK
            is IOException -> if (current.message?.contains("ENOSPC") == true) return DownloadFailureReason.STORAGE
        }
        current = current.cause
    }
    return DownloadFailureReason.UNEXPECTED
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
