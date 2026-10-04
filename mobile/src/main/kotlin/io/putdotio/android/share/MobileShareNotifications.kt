package io.putdotio.android.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.putdotio.android.MainActivity
import io.putdotio.android.R

/** The share service's one notification in each of its states; none carries the export's payload. */
internal class MobileShareNotifications(private val context: Context) {
    fun progress(name: String, indeterminate: Boolean, progress: Int): Notification =
        base(context.getString(R.string.mobile_share_preparing, name))
            .setProgress(EXPORT_PROGRESS_MAX, progress, indeterminate)
            .setOngoing(true)
            .addAction(
                0,
                context.getString(R.string.mobile_action_cancel),
                PendingIntent.getService(
                    context,
                    0,
                    Intent(context, MobileFileShareService::class.java)
                        .setAction(MobileFileShareService.ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    /** Tapping returns to the app, whose resume opens the chooser; the payload stays out of the notification. */
    fun ready(name: String): Notification =
        base(context.getString(R.string.mobile_share_ready, name)).setAutoCancel(true).build()

    fun failed(name: String): Notification =
        base(context.getString(R.string.mobile_share_failed, name)).setAutoCancel(true).build()

    private fun base(text: String): NotificationCompat.Builder {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.mobile_share_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ph_arrow_circle_down)
            .setContentTitle(context.getString(R.string.mobile_files_share))
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
    }

    private companion object {
        const val CHANNEL_ID = "share"
    }
}
