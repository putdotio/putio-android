package io.putdotio.android.downloads

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import io.putdotio.android.MobileDeepLink
import io.putdotio.android.R
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.pendingIntent
import io.putdotio.android.files.FilesItemId

/** What a finished transfer tells the viewer; Media3 reports both on its process-wide manager. */
internal sealed interface DownloadOutcome {
    data object Completed : DownloadOutcome

    data class Failed(val reason: DownloadFailureReason) : DownloadOutcome
}

/**
 * Tells the viewer when a download finishes or fails, with or without the app open. The name
 * comes from the owner's index row; no URL or token reaches a notification. Each file has one
 * slot, replaced by its next outcome and cleared by a retry, a delete or the owner's sign-out; a
 * tap opens the row in Downloads, and its actions play a finished copy or retry a failed one.
 * Nothing is posted while the app's notifications are off, the channel is blocked, or, from
 * Android 13, POST_NOTIFICATIONS is not granted, nor for an account other than the one signed in now.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MobileDownloadNotifications(
    private val context: Context,
    /** The signed-in account's user id, from the auth state; null while nobody is signed in. */
    private val signedInUser: () -> Long?,
) : DownloadManager.Listener {
    override fun onDownloadChanged(
        downloadManager: DownloadManager,
        download: Download,
        finalException: Exception?,
    ) {
        // Most changes are progress; only outcomes read the index.
        val outcome = when (download.state) {
            Download.STATE_COMPLETED -> DownloadOutcome.Completed
            Download.STATE_FAILED -> DownloadOutcome.Failed(finalException.toFailureReason())
            else -> return
        }
        val userId = download.request.ownerUserId()?.takeIf { it == signedInUser() } ?: return
        val fileId = download.request.id.substringAfter(':').toLongOrNull()?.takeIf { it > 0L }?.let(::FilesItemId)
        // A row being deleted, or one this device never indexed, stays quiet.
        val entry = fileId?.let { MobileDownloadStore.peek(context, userId, it) }
        if (entry?.removing == false) post(context, userId, entry, outcome)
    }

    companion object {
        private const val CHANNEL_ID = "download-updates"
        private const val NOTIFICATION_ID = 2002

        /** Whether an outcome would be shown now; the Downloads screen offers to turn them on otherwise. */
        fun canPost(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            val channelImportance = manager.getNotificationChannelCompat(CHANNEL_ID)?.importance
            val channelBlocked = channelImportance == NotificationManagerCompat.IMPORTANCE_NONE
            return granted && manager.areNotificationsEnabled() && !channelBlocked
        }

        fun post(context: Context, userId: Long, entry: DownloadEntry, outcome: DownloadOutcome) {
            if (!canPost(context)) return
            val manager = NotificationManagerCompat.from(context)
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.mobile_downloads_updates_channel_name))
                    .setDescription(context.getString(R.string.mobile_downloads_updates_channel_description))
                    .build(),
            )
            val notification = outcomeNotification(context, userId, entry, outcome).build()
            // The grant can be revoked between the check and this call.
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    manager.notify(tag(userId, entry.fileId), NOTIFICATION_ID, notification)
                }
            } catch (_: SecurityException) {
                // Nothing to show; the Downloads row carries the same outcome.
            }
        }

        fun cancel(context: Context, userId: Long, fileId: FilesItemId) {
            NotificationManagerCompat.from(context).cancel(tag(userId, fileId), NOTIFICATION_ID)
        }

        /** A session ended: outcomes of every account but [signedInUser], who may already be the next one, go. */
        fun cancelOtherAccounts(context: Context, signedInUser: Long?) {
            val manager = NotificationManagerCompat.from(context)
            for (posted in manager.activeNotifications) {
                val owner = posted.tag?.takeIf { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)
                    ?.substringBefore(':')?.toLongOrNull() ?: continue
                if (posted.id == NOTIFICATION_ID && owner != signedInUser) manager.cancel(posted.tag, posted.id)
            }
        }

        /**
         * One outcome's notification. Its tap and actions carry [userId], so they do nothing under
         * another account: Play and Open route through MainActivity's product links, Retry through
         * [MobileDownloadActionReceiver], which only this app can reach.
         */
        internal fun outcomeNotification(
            context: Context,
            userId: Long,
            entry: DownloadEntry,
            outcome: DownloadOutcome,
        ): NotificationCompat.Builder {
            val title = context.getString(
                when (outcome) {
                    DownloadOutcome.Completed -> R.string.mobile_downloads_notification_completed
                    is DownloadOutcome.Failed -> R.string.mobile_downloads_notification_failed
                },
            )
            val text = when (outcome) {
                DownloadOutcome.Completed -> entry.name
                is DownloadOutcome.Failed -> context.getString(
                    R.string.mobile_downloads_notification_failed_text,
                    entry.name,
                    context.getString(outcome.reason.message()),
                )
            }
            val icon = when (outcome) {
                DownloadOutcome.Completed -> R.drawable.ic_ph_check
                is DownloadOutcome.Failed -> R.drawable.ic_ph_warning_circle
            }
            // The lock screen shows the outcome without the file name.
            val public = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .build()
            val openRow = MobileDeepLink.Downloads(entry.fileId, userId = userId).pendingIntent(context)
            val primary = when (outcome) {
                DownloadOutcome.Completed -> NotificationCompat.Action(
                    DesignR.drawable.ic_ph_play_fill,
                    context.getString(R.string.mobile_downloads_play),
                    MobileDeepLink.Downloads(entry.fileId, play = true, userId = userId).pendingIntent(context),
                )
                is DownloadOutcome.Failed -> NotificationCompat.Action(
                    R.drawable.ic_ph_arrow_clockwise,
                    context.getString(R.string.mobile_action_retry),
                    MobileDownloadActionReceiver.retry(context, userId, entry.fileId),
                )
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(entry.name)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(openRow)
                .addAction(primary)
                .addAction(
                    R.drawable.ic_ph_arrow_circle_down,
                    context.getString(R.string.mobile_downloads_notification_open),
                    openRow,
                )
        }

        private const val TAG_PREFIX = "download:"

        private fun tag(userId: Long, fileId: FilesItemId): String = "$TAG_PREFIX$userId:${fileId.value}"
    }
}

/** The sentence a failed row and its notification show. */
@StringRes
internal fun DownloadFailureReason.message(): Int =
    when (this) {
        DownloadFailureReason.NETWORK -> R.string.mobile_downloads_status_failed_network
        DownloadFailureReason.AUTHENTICATION -> R.string.mobile_downloads_status_failed_authentication
        DownloadFailureReason.UNAVAILABLE -> R.string.mobile_downloads_status_failed_unavailable
        DownloadFailureReason.STORAGE -> R.string.mobile_downloads_status_failed_storage
        DownloadFailureReason.UNEXPECTED -> R.string.mobile_downloads_status_failed_unexpected
    }
