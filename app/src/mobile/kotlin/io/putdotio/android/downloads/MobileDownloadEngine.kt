package io.putdotio.android.downloads

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.core.net.toUri
import io.putdotio.android.files.FilesItemId
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import androidx.media3.datasource.HttpDataSource

/**
 * Bridges the app's download index to Media3. Requests carry the token-free
 * API URL; Media3 persists them, resumes them, and reports progress back here.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MobileDownloadEngine(
    context: Context,
    private val store: MobileDownloadStore,
    private val userId: Long,
    private val downloadManager: DownloadManager = MobileDownloadCache.get(context).downloadManager,
) : DownloadEngine, DownloadManager.Listener {
    private val appContext = context.applicationContext

    init {
        downloadManager.addListener(this)
        // Reconcile rows written before the process died against Media3's own index.
        for (download in downloadManager.currentDownloads) reflect(download, null)
    }

    override fun start(entry: DownloadEntry) {
        val request = DownloadRequest.Builder(contentId(entry.fileId), entry.artifact.apiUri(entry.fileId))
            .setMimeType(if (entry.artifact == DownloadArtifact.HLS) MimeTypes.APPLICATION_M3U8 else null)
            .build()
        DownloadService.sendAddDownload(appContext, MobileDownloadService::class.java, request, true)
    }

    override fun remove(fileId: FilesItemId) {
        DownloadService.sendRemoveDownload(appContext, MobileDownloadService::class.java, contentId(fileId), false)
    }

    fun close() {
        downloadManager.removeListener(this)
    }

    override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
        reflect(download, finalException)
    }

    // Media3 keeps queued rows queued while offline; the rows show why nothing moves.
    override fun onWaitingForRequirementsChanged(downloadManager: DownloadManager, waitingForRequirements: Boolean) {
        for (download in downloadManager.currentDownloads) reflect(download, null)
    }

    private fun reflect(download: Download, error: Exception?) {
        val fileId = fileIdOf(download.request.id) ?: return
        val status = download.toStatus(error, downloadManager.isWaitingForRequirements) ?: return
        store.updateStatusBlocking(fileId) { it.copy(status = status) }
    }

    private fun contentId(fileId: FilesItemId): String = "$userId:${fileId.value}"

    private fun fileIdOf(contentId: String): FilesItemId? {
        val (owner, file) = contentId.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        if (owner.toLongOrNull() != userId) return null
        return file.toLongOrNull()?.takeIf { it > 0L }?.let(::FilesItemId)
    }

    /** Playable item for a completed download; the cache data source serves it offline. */
    fun mediaItem(entry: DownloadEntry): MediaItem =
        MediaItem.Builder()
            .setMediaId(entry.fileId.value.toString())
            .setUri(entry.artifact.apiUri(entry.fileId))
            .setMimeType(if (entry.artifact == DownloadArtifact.HLS) MimeTypes.APPLICATION_M3U8 else null)
            .build()
}

internal fun DownloadArtifact.apiUrl(fileId: FilesItemId): String =
    when (this) {
        DownloadArtifact.HLS -> "https://api.put.io/v2/files/${fileId.value}/hls/media.m3u8?subtitle_key=all"
        DownloadArtifact.ORIGINAL -> "https://api.put.io/v2/files/${fileId.value}/stream"
    }

private fun DownloadArtifact.apiUri(fileId: FilesItemId): android.net.Uri = apiUrl(fileId).toUri()

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
        // Paused by requirements (no network) shows as retryable rather than stuck.
        Download.STATE_STOPPED -> DownloadStatus.Failed(DownloadFailureReason.NETWORK, bytesDownloaded)
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
