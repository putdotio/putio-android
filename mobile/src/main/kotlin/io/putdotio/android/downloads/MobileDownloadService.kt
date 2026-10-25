package io.putdotio.android.downloads

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import io.putdotio.android.R
import io.putdotio.android.auth.MobileOAuthRuntime

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
