package io.putdotio.android.downloads

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
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
import io.putdotio.android.playback.DirectPlayDownload
import io.putdotio.android.toRouteUri
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MobileDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val link = parseMobileDeepLink(intent.data) as? MobileDeepLink.Downloads
        val userId = link?.userId ?: return
        val work = buildAction(context.applicationContext, intent.action, userId, link.fileId) ?: return
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

        @Volatile
        internal var retriesForTest: DownloadNotificationRetries? = null

        @Volatile
        internal var controlsForTest: DownloadNotificationControls? = null

        @VisibleForTesting
        internal var retriesFactoryForTest: ((Context) -> DownloadNotificationRetries)? = null

        @VisibleForTesting
        internal var controlsFactoryForTest: ((Context) -> DownloadNotificationControls)? = null

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val SESSION_TIMEOUT = 5.seconds

        fun retry(context: Context, userId: Long, fileId: FilesItemId): PendingIntent =
            broadcast(context, ACTION_RETRY, MobileDeepLink.Downloads(fileId, userId = userId))

        fun pauseAll(context: Context, userId: Long): PendingIntent =
            broadcast(context, ACTION_PAUSE, MobileDeepLink.Downloads(userId = userId))

        fun cancelAll(context: Context, userId: Long): PendingIntent =
            broadcast(context, ACTION_CANCEL, MobileDeepLink.Downloads(userId = userId))

        private fun broadcast(context: Context, action: String, link: MobileDeepLink.Downloads): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(action, link.toRouteUri()).setClass(context, MobileDownloadActionReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun buildAction(
            appContext: Context,
            action: String?,
            userId: Long,
            fileId: FilesItemId?,
        ): (suspend () -> Unit)? =
            when {
                action == ACTION_RETRY && fileId != null -> {
                    {
                        val retries = retriesForTest ?: (retriesFactoryForTest ?: ::production)(appContext)
                        retries.retry(userId, fileId)
                    }
                }
                action == ACTION_PAUSE && fileId == null -> {
                    {
                        val controls = controlsForTest
                            ?: (controlsFactoryForTest ?: ::productionControls)(appContext)
                        controls.pauseAll(userId)
                    }
                }
                action == ACTION_CANCEL && fileId == null -> {
                    {
                        val controls = controlsForTest
                            ?: (controlsFactoryForTest ?: ::productionControls)(appContext)
                        controls.cancelAll(userId)
                    }
                }
                else -> null
            }

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

        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        private fun production(context: Context): DownloadNotificationRetries {
            val downloads = MobileDownloadCache.get(context)
            val runtime = MobileOAuthRuntime.get(context)
            val directPlay = mobileDirectPlayDownloads(context, runtime.putioClient)
            return DownloadNotificationRetries(
                signedIn = signedInAccount(context),
                find = { userId, fileId -> MobileDownloadStore.peek(context, userId, fileId) },
                start = { userId, entry ->
                    withContext(Dispatchers.Main.immediate) {
                        val files = runtime.putioClient.files
                        downloads.dropReplacedBytes(downloads.downloadManager, userId, entry, Dispatchers.IO, files)
                        entry.sendDownloadRequest(context, userId, files)
                    }
                },
                dismiss = { userId, fileId -> MobileDownloadNotifications.cancel(context, userId, fileId) },
                decision = DownloadRetryDecision(
                    directPlay = { fileId, subtitles, chosen ->
                        directPlay.resolve(fileId, subtitles, RETRY_LOOKUP_BUDGET, chosen)
                    },
                    save = { userId, entry -> MobileDownloadStore.shared(context, userId).upsert(entry) },
                    failed = { userId, entry ->
                        MobileDownloadNotifications.post(
                            context, userId, entry, DownloadOutcome.Failed(DownloadFailureReason.UNAVAILABLE),
                        )
                    },
                ),
            )
        }

        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        private fun productionControls(context: Context): DownloadNotificationControls {
            val downloads = MobileDownloadCache.get(context)
            return DownloadNotificationControls(
                signedIn = signedInAccount(context),
                live = { userId ->
                    withContext(Dispatchers.Main.immediate) { downloads.downloadManager.liveDownloadsOf(userId) }
                },
                pause = { userId, fileId ->
                    val manager = withContext(Dispatchers.Main.immediate) { downloads.downloadManager }
                    pauseFromNotification(MobileDownloadStore.shared(context, userId), manager, userId, fileId)
                },
                cancel = { userId, fileId ->
                    cancelFromNotification(MobileDownloadStore.shared(context, userId), userId, fileId) {
                        withContext(Dispatchers.Main.immediate) {
                            val manager = downloads.downloadManager
                            try {
                                DownloadService.sendRemoveDownload(
                                    context,
                                    MobileDownloadService::class.java,
                                    it,
                                    false,
                                )
                            } catch (_: IllegalStateException) {
                                manager.removeDownload(it)
                            }
                        }
                    }
                },
            )
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun DownloadManager.liveDownloadsOf(userId: Long): List<FilesItemId> =
    currentDownloads
        .filter { it.stopReason == Download.STOP_REASON_NONE && it.state != Download.STATE_REMOVING }
        .mapNotNull { fileIdOf(userId, it.request.id) }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal suspend fun pauseFromNotification(
    store: MobileDownloadStore,
    manager: DownloadManager,
    userId: Long,
    fileId: FilesItemId,
) {
    if (store.find(fileId)?.removing == true) return
    store.updateStatus(fileId) { it.copy(pauseRequested = true) }
    withContext(Dispatchers.Main.immediate) {
        manager.setStopReason(downloadContentId(userId, fileId), MobileDownloadEngine.STOP_REASON_PAUSED)
    }
}

internal suspend fun cancelFromNotification(
    store: MobileDownloadStore,
    userId: Long,
    fileId: FilesItemId,
    remove: suspend (contentId: String) -> Unit,
) {
    store.updateStatus(fileId) { it.copy(removing = true) }
    remove(downloadContentId(userId, fileId))
}

internal sealed interface SignedInAccount {
    data class User(val userId: Long) : SignedInAccount

    data object Nobody : SignedInAccount

    data object Unknown : SignedInAccount
}

internal enum class DownloadRetryOutcome {
    STARTED,

    REFUSED,

    NOT_RETRYABLE,

    UNCONFIRMED,

    UNAVAILABLE,
}

internal class DownloadRetryDecision(
    val directPlay: suspend (FilesItemId, includeSubtitles: Boolean, chosen: Boolean) -> DirectPlayDownload? =
        { _, _, _ -> null },
    val save: suspend (userId: Long, entry: DownloadEntry) -> Unit = { _, _ -> },
    val failed: (userId: Long, entry: DownloadEntry) -> Unit = { _, _ -> },
)

internal class DownloadNotificationRetries(
    private val signedIn: suspend () -> SignedInAccount,
    private val find: (userId: Long, fileId: FilesItemId) -> DownloadEntry?,
    private val start: suspend (userId: Long, entry: DownloadEntry) -> Unit,
    private val dismiss: (userId: Long, fileId: FilesItemId) -> Unit,
    private val decision: DownloadRetryDecision = DownloadRetryDecision(),
) {
    suspend fun retry(userId: Long, fileId: FilesItemId): DownloadRetryOutcome {
        val outcome = when (signedIn()) {
            SignedInAccount.Unknown -> DownloadRetryOutcome.UNCONFIRMED
            SignedInAccount.User(userId) ->
                find(userId, fileId)?.takeIf { it.canRetry && !it.removing }?.let { queue(userId, it) }
                    ?: DownloadRetryOutcome.NOT_RETRYABLE
            else -> DownloadRetryOutcome.REFUSED
        }
        if (outcome == DownloadRetryOutcome.REFUSED || outcome == DownloadRetryOutcome.NOT_RETRYABLE) {
            dismiss(userId, fileId)
        }
        return outcome
    }

    private suspend fun queue(userId: Long, entry: DownloadEntry): DownloadRetryOutcome {
        val retry = entry.retryStart()
        val answer = if (retry.asksDirectPlay) {
            decision.directPlay(entry.fileId, entry.subtitlesHidden != true, entry.directPlayChosen)
        } else {
            null
        }
        return when {
            retry.asksDirectPlay && signedIn() != SignedInAccount.User(userId) -> DownloadRetryOutcome.REFUSED
            retry.asksDirectPlay && find(userId, entry.fileId) != entry -> DownloadRetryOutcome.NOT_RETRYABLE
            answer.unavailable -> {
                decision.failed(userId, entry)
                DownloadRetryOutcome.UNAVAILABLE
            }
            retry.asksDirectPlay -> startDecided(userId, entry, retry.entry.decidedBy(answer))
            else -> startDecided(userId, entry, retry.entry)
        }
    }

    private suspend fun startDecided(userId: Long, entry: DownloadEntry, chosen: DownloadEntry): DownloadRetryOutcome {
        val saved = entry.copy(
            artifact = chosen.artifact,
            subtitles = chosen.subtitles,
            directPlayUnanswered = chosen.directPlayUnanswered,
        )
        if (saved != entry) decision.save(userId, saved)
        return try {
            start(userId, chosen)
            DownloadRetryOutcome.STARTED
        } catch (_: IllegalStateException) {
            DownloadRetryOutcome.UNCONFIRMED
        }
    }
}

private val RETRY_LOOKUP_BUDGET = 4.seconds

internal class DownloadNotificationControls(
    private val signedIn: suspend () -> SignedInAccount,
    private val live: suspend (userId: Long) -> List<FilesItemId>,
    private val pause: suspend (userId: Long, fileId: FilesItemId) -> Unit,
    private val cancel: suspend (userId: Long, fileId: FilesItemId) -> Unit,
) {
    suspend fun pauseAll(userId: Long): Int = forEachLive(userId, pause)

    suspend fun cancelAll(userId: Long): Int = forEachLive(userId, cancel)

    private suspend fun forEachLive(userId: Long, action: suspend (Long, FilesItemId) -> Unit): Int {
        if (signedIn() != SignedInAccount.User(userId)) return 0
        val fileIds = live(userId)
        for (fileId in fileIds) action(userId, fileId)
        return fileIds.size
    }
}
