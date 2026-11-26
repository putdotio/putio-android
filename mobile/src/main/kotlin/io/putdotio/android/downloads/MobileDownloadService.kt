package io.putdotio.android.downloads

import android.app.Notification
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import io.putdotio.android.R
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.sdk.files.FilesApi
import io.putdotio.sdk.files.PutioFileType

/**
 * Foreground host for Media3 downloads. Its notification ([downloadProgressNotification]) shows the
 * count, progress and the first running name on an unlocked screen; URLs and tokens never reach it.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MobileDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.mobile_downloads_channel_name,
    0,
) {
    override fun getDownloadManager(): DownloadManager {
        val downloads = MobileDownloadCache.get(this)
        MobileOAuthRuntime.get(this).ensureSessionRestored(downloads::markSessionSettled)
        return downloads.downloadManager
    }

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        downloadProgressNotification(this, CHANNEL_ID, downloads, notMetRequirements)

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 2001
        const val JOB_ID = 2001
        const val CHANNEL_ID = "downloads"
    }
}

/**
 * Hands [userId]'s download to Media3 through the download service: a new request, or a failed or
 * missing one again, which joins the back of the queue. Its last outcome notification goes once the
 * service has the request; a start the system refuses throws and leaves it, Try again included.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun DownloadEntry.sendDownloadRequest(
    context: Context,
    userId: Long,
    files: FilesApi,
    stopReason: Int = this.stopReason,
    send: (DownloadRequest, Int) -> Unit = { request, reason ->
        DownloadService.sendAddDownload(context, MobileDownloadService::class.java, request, reason, true)
    },
) {
    send(downloadRequest(userId, files), stopReason)
    MobileDownloadNotifications.cancel(context, userId, fileId)
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun DownloadEntry.startDownload(
    context: Context,
    manager: DownloadManager,
    userId: Long,
    files: FilesApi,
    stopReason: Int = this.stopReason,
) {
    try {
        sendDownloadRequest(context, userId, files, stopReason)
    } catch (_: IllegalStateException) {
        sendDownloadRequest(context, userId, files, stopReason) { request, reason ->
            manager.addDownload(request, reason)
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun DownloadEntry.downloadRequest(userId: Long, files: FilesApi): DownloadRequest {
    val url = artifact.apiUrl(fileId, files, subtitlesHidden = subtitlesHidden == true)
    return DownloadRequest.Builder(downloadContentId(userId, fileId), url.toUri())
        .setMimeType(requestMimeType)
        .setData(downloadRequestData(name, subtitles))
        .build()
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun DownloadRequest.fetchesSameAs(other: DownloadRequest): Boolean =
    uri == other.uri &&
        data.toDownloadRequestData().subtitles.map { it.copy(isDefault = false) } ==
        other.data.toDownloadRequestData().subtitles.map { it.copy(isDefault = false) }

private val DownloadEntry.requestMimeType: String?
    get() = when {
        artifact.isHls -> MimeTypes.APPLICATION_M3U8
        type == PutioFileType.VIDEO -> VIDEO_FILE_MIME_TYPE
        else -> null
    }

@get:androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private val DownloadEntry.stopReason: Int
    get() = if (pauseRequested) MobileDownloadEngine.STOP_REASON_PAUSED else Download.STOP_REASON_NONE
