package io.putdotio.android.downloads

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import io.putdotio.android.MobileDeepLink
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.holdBroadcast
import io.putdotio.android.parseMobileDeepLink
import io.putdotio.android.toRouteUri
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A download notification's actions: Try again on a failed download, Pause and Cancel on the running
 * one. Not exported, so only this app's own pending intents reach it, and the work runs without
 * opening the app.
 */
internal class MobileDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val link = parseMobileDeepLink(intent.data) as? MobileDeepLink.Downloads
        val userId = link?.userId ?: return
        val fileId = link.fileId
        val work: (suspend () -> Unit)? = when {
            intent.action == ACTION_RETRY && fileId != null -> {
                val retries = retriesForTest ?: production(context.applicationContext)
                suspend { retries.retry(userId, fileId) }
            }
            intent.action == ACTION_PAUSE && fileId == null -> {
                val controls = controlsForTest ?: productionControls(context.applicationContext)
                suspend { controls.pauseAll(userId) }
            }
            intent.action == ACTION_CANCEL && fileId == null -> {
                val controls = controlsForTest ?: productionControls(context.applicationContext)
                suspend { controls.cancelAll(userId) }
            }
            else -> null
        }
        if (work == null) return
        val hold = holdBroadcast()
        scope.launch {
            try {
                work()
            } finally {
                hold.release()
            }
        }
    }

    internal companion object {
        const val ACTION_RETRY = "io.putdotio.android.downloads.action.RETRY"
        const val ACTION_PAUSE = "io.putdotio.android.downloads.action.PAUSE"
        const val ACTION_CANCEL = "io.putdotio.android.downloads.action.CANCEL"

        /** The proof lane replaces the session and the transfer with its own. */
        @Volatile
        internal var retriesForTest: DownloadNotificationRetries? = null

        @Volatile
        internal var controlsForTest: DownloadNotificationControls? = null

        // Media3's manager and the store's blocking writes belong on the main thread, as in the engine.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /**
         * Restoring a session in a cold process reads put.io. Android lets an app start a foreground
         * service for about 10 s after a notification tap, so past this the tap does nothing and the
         * notification stays for another tap, which finds the session already restored.
         */
        private val SESSION_TIMEOUT = 5.seconds

        /** An explicit, immutable broadcast; the data keeps one pending intent per account and file. */
        fun retry(context: Context, userId: Long, fileId: FilesItemId): PendingIntent =
            broadcast(context, ACTION_RETRY, MobileDeepLink.Downloads(fileId, userId = userId))

        /** Pauses every live download of [userId]; each stays paused until Resume in Downloads. */
        fun pauseAll(context: Context, userId: Long): PendingIntent =
            broadcast(context, ACTION_PAUSE, MobileDeepLink.Downloads(userId = userId))

        /** Cancels every live download of [userId], as Remove does for an unfinished row. */
        fun cancelAll(context: Context, userId: Long): PendingIntent =
            broadcast(context, ACTION_CANCEL, MobileDeepLink.Downloads(userId = userId))

        private fun broadcast(context: Context, action: String, link: MobileDeepLink.Downloads): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(action, link.toRouteUri()).setClass(context, MobileDownloadActionReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun signedInAccount(context: Context): suspend () -> SignedInAccount {
            val runtime = MobileOAuthRuntime.get(context)
            return {
                when (val state = runtime.awaitSettledSession(SESSION_TIMEOUT)) {
                    is MobileAuthState.SignedIn -> SignedInAccount.User(state.account.userId)
                    is MobileAuthState.SignedOut -> SignedInAccount.Nobody
                    else -> SignedInAccount.Unknown
                }
            }
        }

        private fun production(context: Context): DownloadNotificationRetries =
            DownloadNotificationRetries(
                signedIn = signedInAccount(context),
                find = { userId, fileId -> MobileDownloadStore.peek(context, userId, fileId) },
                start = { userId, entry -> sendDownloadRequest(context, userId, entry) },
                dismiss = { userId, fileId -> MobileDownloadNotifications.cancel(context, userId, fileId) },
            )

        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        private fun productionControls(context: Context): DownloadNotificationControls {
            val manager = MobileDownloadCache.get(context).downloadManager
            return DownloadNotificationControls(
                signedIn = signedInAccount(context),
                live = { userId -> manager.liveDownloadsOf(userId) },
                pause = { userId, fileId ->
                    pauseFromNotification(MobileDownloadStore.shared(context, userId), manager, userId, fileId)
                },
                cancel = { userId, fileId ->
                    cancelFromNotification(MobileDownloadStore.shared(context, userId), userId, fileId) {
                        // The service that posted this notification runs, so the start is allowed; were
                        // it refused, the manager removes the download directly.
                        try {
                            DownloadService.sendRemoveDownload(context, MobileDownloadService::class.java, it, false)
                        } catch (_: IllegalStateException) {
                            manager.removeDownload(it)
                        }
                    }
                },
            )
        }
    }
}

/** [userId]'s downloads on their way: queued or running, not paused, parked or being removed. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun DownloadManager.liveDownloadsOf(userId: Long): List<FilesItemId> =
    currentDownloads
        .filter { it.stopReason == Download.STOP_REASON_NONE && it.state != Download.STATE_REMOVING }
        .mapNotNull { fileIdOf(userId, it.request.id) }

/**
 * The viewer's pause, written to the index before Media3's stop reason as the engine's own Pause
 * does, so a live engine holds Media3 to it and Downloads shows the row paused. Media3 decides what
 * is live: a row can still read failed after a background Try again until an engine reconciles it.
 * Without a row, Media3's stop reason alone stands until reconcile rebuilds the row from it.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun pauseFromNotification(
    store: MobileDownloadStore,
    manager: DownloadManager,
    userId: Long,
    fileId: FilesItemId,
) {
    if (store.find(fileId)?.removing == true) return
    store.updateStatusBlocking(fileId, commit = true) { it.copy(pauseRequested = true) }
    manager.setStopReason(downloadContentId(userId, fileId), MobileDownloadEngine.STOP_REASON_PAUSED)
}

/**
 * Cancels as a confirmed Remove does: the row is marked first, so a process death before Media3
 * deletes the bytes still ends with the row gone on the next start. [remove] takes the request id;
 * production sends it through the download service, whose queue runs it after any add already sent.
 */
internal fun cancelFromNotification(
    store: MobileDownloadStore,
    userId: Long,
    fileId: FilesItemId,
    remove: (contentId: String) -> Unit,
) {
    store.updateStatusBlocking(fileId, commit = true) { it.copy(removing = true) }
    remove(downloadContentId(userId, fileId))
}

/** Who is signed in once the session settles; unknown while put.io cannot confirm a stored session. */
internal sealed interface SignedInAccount {
    data class User(val userId: Long) : SignedInAccount

    data object Nobody : SignedInAccount

    data object Unknown : SignedInAccount
}

internal enum class DownloadRetryOutcome {
    /** Media3 queues the download again; the row and notification follow its transfer. */
    STARTED,

    /** Another account, or nobody, is signed in: the notification goes and nothing else happens. */
    REFUSED,

    /** The row is gone, deleting, or no longer failed; the stale notification goes. */
    NOT_RETRYABLE,

    /** The session could not be confirmed in time, or the service could not start; the notification stays. */
    UNCONFIRMED,
}

/** A notification's Retry, acting only for the account that owns the download and only while it is signed in. */
internal class DownloadNotificationRetries(
    private val signedIn: suspend () -> SignedInAccount,
    private val find: (userId: Long, fileId: FilesItemId) -> DownloadEntry?,
    private val start: (userId: Long, entry: DownloadEntry) -> Unit,
    private val dismiss: (userId: Long, fileId: FilesItemId) -> Unit,
) {
    suspend fun retry(userId: Long, fileId: FilesItemId): DownloadRetryOutcome {
        val outcome = when (signedIn()) {
            SignedInAccount.Unknown -> DownloadRetryOutcome.UNCONFIRMED
            SignedInAccount.User(userId) ->
                find(userId, fileId)?.takeIf { it.canRetry && !it.removing }?.let { queue(userId, it) }
                    ?: DownloadRetryOutcome.NOT_RETRYABLE
            else -> DownloadRetryOutcome.REFUSED
        }
        // Another account's outcome, or a stale one, must not stay on screen.
        if (outcome == DownloadRetryOutcome.REFUSED || outcome == DownloadRetryOutcome.NOT_RETRYABLE) {
            dismiss(userId, fileId)
        }
        return outcome
    }

    private fun queue(userId: Long, entry: DownloadEntry): DownloadRetryOutcome =
        try {
            start(userId, entry)
            DownloadRetryOutcome.STARTED
        } catch (_: IllegalStateException) {
            // The system refused to start the download service from the background; Downloads still offers it.
            DownloadRetryOutcome.UNCONFIRMED
        }
}

/**
 * The running notification's Pause and Cancel. Each acts on every live download of the account the
 * notification was built for, only while that account is signed in, and returns how many it reached.
 */
internal class DownloadNotificationControls(
    private val signedIn: suspend () -> SignedInAccount,
    private val live: (userId: Long) -> List<FilesItemId>,
    private val pause: (userId: Long, fileId: FilesItemId) -> Unit,
    private val cancel: (userId: Long, fileId: FilesItemId) -> Unit,
) {
    suspend fun pauseAll(userId: Long): Int = forEachLive(userId, pause)

    suspend fun cancelAll(userId: Long): Int = forEachLive(userId, cancel)

    private suspend fun forEachLive(userId: Long, action: (Long, FilesItemId) -> Unit): Int {
        if (signedIn() != SignedInAccount.User(userId)) return 0
        val fileIds = live(userId)
        for (fileId in fileIds) action(userId, fileId)
        return fileIds.size
    }
}
