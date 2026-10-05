package io.putdotio.android.downloads

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * Retry on a failed download's notification. Not exported, so only this app's own pending intent
 * reaches it, and the work runs without opening the app.
 */
internal class MobileDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val link = (parseMobileDeepLink(intent.data) as? MobileDeepLink.Downloads)
            ?.takeIf { intent.action == ACTION_RETRY }
        val userId = link?.userId
        val fileId = link?.fileId
        if (userId == null || fileId == null) return
        val retries = retriesForTest ?: production(context.applicationContext)
        val hold = holdBroadcast()
        scope.launch {
            try {
                retries.retry(userId, fileId)
            } finally {
                hold.release()
            }
        }
    }

    internal companion object {
        const val ACTION_RETRY = "io.putdotio.android.downloads.action.RETRY"

        /** The proof lane replaces the session and the transfer with its own. */
        @Volatile
        internal var retriesForTest: DownloadNotificationRetries? = null

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /**
         * Restoring a session in a cold process reads put.io. Android lets an app start a foreground
         * service for about 10 s after a notification tap, so past this the tap does nothing and the
         * notification stays for another tap, which finds the session already restored.
         */
        private val SESSION_TIMEOUT = 5.seconds

        /** An explicit, immutable broadcast; the data keeps one pending intent per account and file. */
        fun retry(context: Context, userId: Long, fileId: FilesItemId): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_RETRY, MobileDeepLink.Downloads(fileId, userId = userId).toRouteUri())
                    .setClass(context, MobileDownloadActionReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun production(context: Context): DownloadNotificationRetries {
            val runtime = MobileOAuthRuntime.get(context)
            return DownloadNotificationRetries(
                signedIn = {
                    when (val state = runtime.awaitSettledSession(SESSION_TIMEOUT)) {
                        is MobileAuthState.SignedIn -> SignedInAccount.User(state.account.userId)
                        is MobileAuthState.SignedOut -> SignedInAccount.Nobody
                        else -> SignedInAccount.Unknown
                    }
                },
                find = { userId, fileId -> MobileDownloadStore.peek(context, userId, fileId) },
                start = { userId, entry -> sendDownloadRequest(context, userId, entry) },
                dismiss = { userId, fileId -> MobileDownloadNotifications.cancel(context, userId, fileId) },
            )
        }
    }
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
