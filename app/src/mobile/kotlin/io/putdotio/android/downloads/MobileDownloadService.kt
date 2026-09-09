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
import io.putdotio.android.auth.MobileOAuthRuntime

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
    // A scheduler or boot restart reaches here before any UI: bringing the auth runtime
    // up restores the stored session token into the download resolver.
    override fun getDownloadManager(): DownloadManager {
        val downloads = MobileDownloadCache.get(this)
        MobileOAuthRuntime.get(this).ensureSessionRestored(downloads::markSessionSettled)
        return downloads.downloadManager
    }

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    // Parked transfers of a signed-out account stay in Media3's list; the notification shows only live work.
    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        val active = downloads.filter { it.stopReason == Download.STOP_REASON_NONE }
        return DownloadNotificationHelper(this, CHANNEL_ID).buildProgressNotification(
            this,
            R.drawable.ic_ph_arrow_circle_down,
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
            resources.getQuantityString(R.plurals.mobile_downloads_notification, active.size, active.size),
            active,
            notMetRequirements,
        )
    }

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 2001
        const val JOB_ID = 2001
        const val CHANNEL_ID = "downloads"
    }
}
