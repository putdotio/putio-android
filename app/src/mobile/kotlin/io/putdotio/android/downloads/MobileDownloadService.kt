package io.putdotio.android.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import io.putdotio.android.MainActivity
import io.putdotio.android.R

/**
 * Foreground host for Media3 downloads. The notification shows a count and
 * progress only; names, URLs and tokens never reach it.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MobileDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.mobile_downloads_channel_name,
    0,
) {
    // Restart intents can arrive before any session; a paused stand-in keeps them harmless.
    override fun getDownloadManager(): DownloadManager {
        val downloads = MobileDownloadCache.get(this)
        val userId = downloads.activeUserId ?: return downloads.downloadManager(NO_USER).apply { pauseDownloads() }
        return downloads.downloadManager(userId)
    }

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, CHANNEL_ID).buildProgressNotification(
            this,
            R.drawable.ic_ph_arrow_circle_down,
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
            resources.getQuantityString(R.plurals.mobile_downloads_notification, downloads.size, downloads.size),
            downloads,
            notMetRequirements,
        )

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 2001
        const val JOB_ID = 2001
        const val CHANNEL_ID = "downloads"
        const val NO_USER = -1L
    }
}
