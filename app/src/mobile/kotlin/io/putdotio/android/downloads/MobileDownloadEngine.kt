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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    scope: CoroutineScope,
    private val downloads: MobileDownloadCache = MobileDownloadCache.get(context),
    private val downloadManager: DownloadManager = downloads.downloadManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DownloadEngine, DownloadManager.Listener {
    private val appContext = context.applicationContext
    private val removing = mutableSetOf<FilesItemId>()
    private val parkOnTokenClearing: () -> Unit = { park() }

    init {
        downloads.activeUserId = userId
        downloads.onTokenClearing = parkOnTokenClearing
        downloadManager.addListener(this)
        // The index read is SQLite; only the manager calls must run on its looper.
        scope.launch {
            val snapshot = withContext(ioDispatcher) {
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.download) }
                }
            }
            reconcile(snapshot)
        }
    }

    /** Media3's index is the source of truth for terminal states reached while no UI listened. */
    private fun reconcile(indexed: List<Download>) {
        val known = mutableSetOf<FilesItemId>()
        for (download in indexed) {
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
        // Media3 is the source of truth for bytes: a row it does not know either never
        // reached the service (re-issue it) or was removed while nothing listened (drop it).
        for (entry in store.entries.value) {
            if (entry.fileId in known) continue
            if (entry.accepted) store.removeBlocking(entry.fileId) else start(entry)
        }
        downloadManager.resumeDownloads()
    }

    override fun start(entry: DownloadEntry) {
        val request = DownloadRequest.Builder(contentId(entry.fileId), entry.artifact.apiUrl(entry.fileId).toUri())
            .setMimeType(if (entry.artifact == DownloadArtifact.HLS) MimeTypes.APPLICATION_M3U8 else null)
            .build()
        DownloadService.sendAddDownload(appContext, MobileDownloadService::class.java, request, true)
    }

    override fun remove(fileId: FilesItemId) {
        // The service queue runs a remove after any add already posted, so the intent
        // always goes out; only the row's fate depends on whether Media3 has a record.
        DownloadService.sendRemoveDownload(appContext, MobileDownloadService::class.java, contentId(fileId), false)
        val known = runCatching { downloadManager.downloadIndex.getDownload(contentId(fileId)) }.getOrNull()
        if (known == null) {
            store.removeBlocking(fileId)
        } else {
            synchronized(removing) { removing += fileId }
        }
    }

    /** True while Media3 is still deleting this file's bytes; a new download must wait. */
    override fun isRemoving(fileId: FilesItemId): Boolean = synchronized(removing) { fileId in removing }

    /**
     * Detaches the UI. Transfers keep running: the service owns them and the token
     * stays valid while the user is signed in. A sign-in by another user parks them
     * through that user's reconcile, and a sign-out parks them through [park].
     */
    fun close() {
        downloadManager.removeListener(this)
        if (downloads.activeUserId == userId) downloads.activeUserId = null
        if (downloads.onTokenClearing === parkOnTokenClearing) downloads.onTokenClearing = null
    }

    /** Sign-out: stop this user's transfers before the token disappears; the next sign-in resumes them. */
    fun park() {
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
        store.updateStatusBlocking(fileId) { current ->
            // Reconcile sees the failed state without its exception; the stored reason is better.
            val kept = current.status as? DownloadStatus.Failed
            val next = if (status is DownloadStatus.Failed && error == null && kept != null) kept else status
            current.copy(status = next, accepted = true)
        }
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
        // Parked by sign-out or held by a missing network; both resume without user action.
        Download.STATE_STOPPED ->
            if (waitingForNetwork) DownloadStatus.WaitingForNetwork(bytesDownloaded) else DownloadStatus.Queued
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
