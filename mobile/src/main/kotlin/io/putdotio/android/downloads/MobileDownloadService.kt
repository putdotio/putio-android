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
    // A scheduler or boot restart reaches here before any UI: bringing the auth runtime
    // up restores the stored session token into the download resolver.
    override fun getDownloadManager(): DownloadManager {
        val downloads = MobileDownloadCache.get(this)
        MobileOAuthRuntime.get(this).ensureSessionRestored(downloads::markSessionSettled)
        return downloads.downloadManager
    }

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    // Parked and paused transfers stay in Media3's list; the notification shows only live work.
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
internal fun sendDownloadRequest(
    context: Context,
    userId: Long,
    entry: DownloadEntry,
    stopReason: Int = entry.stopReason,
    send: (DownloadRequest, Int) -> Unit = { request, reason ->
        DownloadService.sendAddDownload(context, MobileDownloadService::class.java, request, reason, true)
    },
) {
    val url = entry.artifact.apiUrl(entry.fileId, subtitlesHidden = entry.subtitlesHidden == true)
    val request = DownloadRequest.Builder(downloadContentId(userId, entry.fileId), url.toUri())
        .setMimeType(entry.requestMimeType)
        .setData(downloadRequestData(entry.name, entry.subtitles))
        .build()
    send(request, stopReason)
    MobileDownloadNotifications.cancel(context, userId, entry.fileId)
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun startDownload(
    context: Context,
    manager: DownloadManager,
    userId: Long,
    entry: DownloadEntry,
    stopReason: Int = entry.stopReason,
) {
    try {
        sendDownloadRequest(context, userId, entry, stopReason)
    } catch (_: IllegalStateException) {
        sendDownloadRequest(context, userId, entry, stopReason) { request, reason ->
            manager.addDownload(request, reason)
        }
    }
}

private val DownloadEntry.requestMimeType: String?
    get() = when {
        artifact.isHls -> MimeTypes.APPLICATION_M3U8
        type == PutioFileType.VIDEO -> VIDEO_FILE_MIME_TYPE
        else -> null
    }

@get:androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private val DownloadEntry.stopReason: Int
    get() = if (pauseRequested) MobileDownloadEngine.STOP_REASON_PAUSED else Download.STOP_REASON_NONE
