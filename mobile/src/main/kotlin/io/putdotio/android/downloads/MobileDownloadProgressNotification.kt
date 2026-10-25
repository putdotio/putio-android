package io.putdotio.android.downloads

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import io.putdotio.android.MobileDeepLink
import io.putdotio.android.R
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.pendingIntent
import kotlin.math.roundToInt

/**
 * The download service's foreground notification: how many files are on their way, the first
 * running one's name with the overall percentage, a bar, and Pause and Cancel for all of them.
 * Only live transfers count; paused, parked and removing ones are not on their way. While Media3
 * deletes cancelled bytes and nothing else is live, it says so without actions. The name shows only
 * on an unlocked screen, and no URL or token reaches the notification.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun downloadProgressNotification(
    context: Context,
    channelId: String,
    downloads: List<Download>,
    notMetRequirements: Int,
): Notification {
    val liveStates = setOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING)
    val live = downloads.filter { it.stopReason == Download.STOP_REASON_NONE && it.state in liveStates }
    val running = live.filter { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING }
    val removingOnly = live.isEmpty() && downloads.any { it.state == Download.STATE_REMOVING }
    val title = if (removingOnly) {
        context.getString(R.string.mobile_downloads_status_removing)
    } else {
        context.resources.getQuantityString(R.plurals.mobile_downloads_notification, live.size, live.size)
    }
    val percents = running.map { it.percentDownloaded }
    // One unknown size makes the overall share unknown, so the bar turns indeterminate.
    val percent = percents.takeIf { it.isNotEmpty() && it.none { value -> value == C.PERCENTAGE_UNSET.toFloat() } }
        ?.average()?.roundToInt()?.coerceIn(0, PERCENT_MAX)
    // Only the signed-in account's transfers run; the others are parked with a stop reason.
    val userId = live.firstNotNullOfOrNull { it.request.ownerUserId() }
    val builder = progressBuilder(context, channelId, title)
        .setPublicVersion(progressBuilder(context, channelId, title).build())
        .setContentIntent(MobileDeepLink.Downloads(userId = userId).pendingIntent(context))
    if (!removingOnly) builder.setContentText(progressText(context, running, percent, notMetRequirements))
    if (running.isNotEmpty()) builder.setProgress(PERCENT_MAX, percent ?: 0, percent == null)
    if (userId != null) {
        builder
            .addAction(
                R.drawable.ic_ph_pause,
                context.getString(R.string.mobile_downloads_pause),
                MobileDownloadActionReceiver.pauseAll(context, userId),
            )
            .addAction(
                R.drawable.ic_ph_x,
                context.getString(R.string.mobile_action_cancel),
                MobileDownloadActionReceiver.cancelAll(context, userId),
            )
    }
    return builder.build()
}

private fun progressBuilder(context: Context, channelId: String, title: String): NotificationCompat.Builder =
    NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_ph_arrow_down)
        .setColor(ContextCompat.getColor(context, DesignR.color.putio_yellow_solid))
        .setContentTitle(title)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setShowWhen(false)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

/** The first running file and the overall percentage, else what the queue waits for. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun progressText(context: Context, running: List<Download>, percent: Int?, notMetRequirements: Int): String =
    when {
        running.isNotEmpty() -> {
            val name = running.first().name(context)
            percent?.let { context.getString(R.string.mobile_downloads_notification_running, name, it) } ?: name
        }
        notMetRequirements and (Requirements.NETWORK or Requirements.NETWORK_UNMETERED) != 0 ->
            context.getString(R.string.mobile_files_download_waiting)
        notMetRequirements and Requirements.DEVICE_STORAGE_NOT_LOW != 0 ->
            context.getString(R.string.mobile_files_download_waiting_storage)
        else -> context.getString(R.string.mobile_downloads_status_queued)
    }

/** The name a request carries for its row; a request without one reads as a recovered download. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun Download.name(context: Context): String =
    request.data.takeIf { it.isNotEmpty() }?.decodeToString()
        ?: context.getString(R.string.mobile_downloads_recovered_name)

private const val PERCENT_MAX = 100
