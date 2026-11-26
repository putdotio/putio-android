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
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.pendingIntent
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.FilesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface DownloadOutcome {
    data object Completed : DownloadOutcome

    data class Failed(val reason: DownloadFailureReason) : DownloadOutcome
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MobileDownloadNotifications(
    private val context: Context,
    private val stores: (Long) -> MobileDownloadStore = { MobileDownloadStore.shared(context, it) },
    private val redeciding: (Long) -> Boolean = DirectPlayRedeciders.shared::holds,
    lookupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val files: () -> FilesApi = { MobileOAuthRuntime.get(context).putioClient.files },
    private val signedInUser: () -> Long?,
) : DownloadManager.Listener {
    private val scope = CoroutineScope(SupervisorJob() + lookupDispatcher)

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
        val owner = download.request.ownerUserId()
        val fileId = owner?.let { fileIdOf(it, download.request.id) }
        if (outcome == null || owner == null || fileId == null) return
        if (download.fallingBackToConverted(finalException)) {
            fallBackIfEligible(downloadManager, owner, fileId)
        } else if (owner == signedInUser()) {
            notifyIfEligible(owner, fileId, download, outcome)
        }
    }

    private fun fallBackIfEligible(manager: DownloadManager, owner: Long, fileId: FilesItemId) {
        scope.launch {
            val store = MobileDownloadStore.existingShared(context, owner) ?: stores(owner)
            var converted: DownloadEntry? = null
            store.updateStatusBlocking(fileId) { current ->
                if (current.removing) {
                    current
                } else {
                    current.convertedFallback()?.also { converted = it } ?: current
                }
            }
            val next = converted ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                if (store.find(fileId)?.removing == false) {
                    val parked = owner != signedInUser()
                    val stopReason = if (parked) {
                        MobileDownloadEngine.STOP_REASON_OTHER_USER
                    } else {
                        Download.STOP_REASON_NONE
                    }
                    next.startDownload(context, manager, owner, files(), stopReason)
                }
            }
        }
    }

    private fun notifyIfEligible(owner: Long, fileId: FilesItemId, download: Download, outcome: DownloadOutcome) {
        val loaded = MobileDownloadStore.existingShared(context, owner)
        if (loaded != null) {
            postIfEligible(owner, loaded.find(fileId), download, outcome)
            return
        }
        scope.launch {
            val entry = MobileDownloadStore.peek(context, owner, fileId)
            if (owner == signedInUser()) {
                withContext(Dispatchers.Main.immediate) {
                    val liveStore = MobileDownloadStore.existingShared(context, owner)
                    val current = if (liveStore != null) liveStore.find(fileId) else entry
                    if (owner == signedInUser()) postIfEligible(owner, current, download, outcome)
                }
            }
        }
    }

    private fun postIfEligible(
        owner: Long,
        entry: DownloadEntry?,
        download: Download,
        outcome: DownloadOutcome,
    ) {
        entry?.takeUnless {
            it.removing || redeciding(owner) && it.redecidesDirectPlay(download.failedStatus(outcome))
        }?.let { post(context, owner, it, outcome) }
    }

    companion object {
        private const val CHANNEL_ID = "download-updates"
        private const val NOTIFICATION_ID = 2002

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

        fun cancelOtherAccounts(context: Context, signedInUser: Long?) {
            val manager = NotificationManagerCompat.from(context)
            for (posted in manager.activeNotifications) {
                val owner = posted.tag?.takeIf { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)
                    ?.substringBefore(':')?.toLongOrNull() ?: continue
                if (posted.id == NOTIFICATION_ID && owner != signedInUser) manager.cancel(posted.tag, posted.id)
            }
        }

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
            val public = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setColor(ContextCompat.getColor(context, DesignR.color.putio_yellow_solid))
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
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setColor(ContextCompat.getColor(context, DesignR.color.putio_yellow_solid))
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
            if (outcome is DownloadOutcome.Failed) {
                builder.addAction(
                    R.drawable.ic_ph_download_simple,
                    context.getString(R.string.mobile_downloads_notification_open),
                    openRow,
                )
            }
            return builder
        }

        private const val TAG_PREFIX = "download:"

        private fun tag(userId: Long, fileId: FilesItemId): String = "$TAG_PREFIX$userId:${fileId.value}"
    }
}

@StringRes
internal fun DownloadFailureReason.message(): Int =
    when (this) {
        DownloadFailureReason.NETWORK -> R.string.mobile_downloads_status_failed_network
        DownloadFailureReason.AUTHENTICATION -> R.string.mobile_downloads_status_failed_authentication
        DownloadFailureReason.UNAVAILABLE -> R.string.mobile_downloads_status_failed_unavailable
        DownloadFailureReason.STORAGE -> R.string.mobile_downloads_status_failed_storage
        DownloadFailureReason.UNEXPECTED -> R.string.mobile_downloads_status_failed_unexpected
    }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun Download.failedStatus(outcome: DownloadOutcome): DownloadStatus? =
    (outcome as? DownloadOutcome.Failed)?.let { DownloadStatus.Failed(it.reason, bytesDownloaded) }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun Download.fallingBackToConverted(error: Exception?): Boolean =
    state == Download.STATE_FAILED && error != null &&
        request.artifact.convertedFallback(request.fileType) != null &&
        error.toFailureReason().fallsBackToConverted(bytesDownloaded)
