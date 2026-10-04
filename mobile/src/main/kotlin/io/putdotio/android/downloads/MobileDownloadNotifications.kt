package io.putdotio.android.downloads

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import io.putdotio.android.MainActivity
import io.putdotio.android.R
import io.putdotio.android.files.FilesItemId

/** What a finished transfer tells the viewer; Media3 reports both on its process-wide manager. */
internal sealed interface DownloadOutcome {
    data object Completed : DownloadOutcome

    data class Failed(val reason: DownloadFailureReason) : DownloadOutcome
}

/**
 * Tells the viewer when a download finishes or fails, with or without the app open. The name
 * comes from the owner's index row; no URL or token reaches a notification. Each file has one
 * slot, replaced by its next outcome and cleared by a retry or a delete; a tap opens the row in
 * Downloads. Nothing is posted while the app's notifications are off, the channel is blocked,
 * or, from Android 13, POST_NOTIFICATIONS is not granted.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MobileDownloadNotifications(private val context: Context) : DownloadManager.Listener {
    override fun onDownloadChanged(
        downloadManager: DownloadManager,
        download: Download,
        finalException: Exception?,
    ) {
        val outcome = when (download.state) {
            Download.STATE_COMPLETED -> DownloadOutcome.Completed
            Download.STATE_FAILED -> DownloadOutcome.Failed(finalException.toFailureReason())
            else -> null
        }
        val userId = download.request.ownerUserId()
        val fileId = download.request.id.substringAfter(':').toLongOrNull()?.takeIf { it > 0L }?.let(::FilesItemId)
        // A row being deleted, or one this device never indexed, stays quiet.
        val entry = if (userId == null || fileId == null) null else MobileDownloadStore.peek(context, userId, fileId)
        if (outcome != null && userId != null && entry?.removing == false) post(context, userId, entry, outcome)
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
            val notification = outcomeNotification(context, entry, outcome).build()
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

        /**
         * One outcome's notification. Actions such as Play or Retry (#41) belong on this builder
         * so the posting, permission and slot rules stay in one place.
         */
        internal fun outcomeNotification(
            context: Context,
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
                .setContentIntent(openRow(context, entry.fileId))
        }

        private fun openRow(context: Context, fileId: FilesItemId): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                // Each file's link is distinct data, so each row keeps its own pending intent.
                Intent(Intent.ACTION_VIEW, "putio://downloads/${fileId.value}".toUri())
                    .setClass(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun tag(userId: Long, fileId: FilesItemId): String = "download:$userId:${fileId.value}"
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
