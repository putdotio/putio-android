package io.putdotio.android.downloads

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.DirectPlayDownload
import io.putdotio.android.playback.DirectPlayDownloadSource
import io.putdotio.sdk.files.FileSubtitle
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileDownloadActionReceiverTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var account: SignedInAccount = SignedInAccount.User(USER)
    private val rows = mutableMapOf<Pair<Long, Long>, DownloadEntry>()
    private val started = mutableListOf<Pair<Long, FilesItemId>>()
    private val dismissed = mutableListOf<Pair<Long, FilesItemId>>()
    private val retries = DownloadNotificationRetries(
        signedIn = { account },
        find = { userId, fileId -> rows[userId to fileId.value] },
        start = { userId, entry -> started += userId to entry.fileId },
        dismiss = { userId, fileId -> dismissed += userId to fileId },
    )

    @After
    fun tearDown() {
        MobileDownloadActionReceiver.retriesForTest = null
        MobileDownloadActionReceiver.controlsForTest = null
        notifications.cancelAll()
    }

    @Test
    fun retryQueuesTheOwnersFailedDownloadAgain() = runBlocking {
        rows[USER to 11L] = row(11L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))

        assertEquals(DownloadRetryOutcome.STARTED, retries.retry(USER, FilesItemId(11L)))

        assertEquals(listOf(USER to FilesItemId(11L)), started)
    }

    @Test
    fun retryDecidesWhatToFetchAsRetryInDownloadsDoes() = runBlocking {
        val untouched = row(11L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))
            .copy(artifact = DownloadArtifact.ORIGINAL_HLS)
        val held = row(12L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 10L))
            .copy(artifact = DownloadArtifact.ORIGINAL_HLS)
        val gone = row(13L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))
            .copy(artifact = DownloadArtifact.MP4)
        for (entry in listOf(untouched, held, gone)) rows[USER to entry.fileId.value] = entry
        val sent = mutableListOf<DownloadEntry>()
        val saved = mutableListOf<DownloadEntry>()
        val failed = mutableListOf<DownloadEntry>()
        val asked = mutableListOf<Long>()
        val deciding = DownloadNotificationRetries(
            signedIn = { account },
            find = { userId, fileId -> rows[userId to fileId.value] },
            start = { _, entry -> sent += entry },
            dismiss = { userId, fileId -> dismissed += userId to fileId },
            decision = DownloadRetryDecision(
                directPlay = { fileId, _ ->
                    asked += fileId.value
                    val source = if (fileId == untouched.fileId) {
                        DirectPlayDownloadSource.Original
                    } else {
                        DirectPlayDownloadSource.Unavailable
                    }
                    DirectPlayDownload(source, listOf(ENGLISH))
                },
                save = { _, entry -> saved += entry },
                failed = { _, entry -> failed += entry },
            ),
        )

        assertEquals(DownloadRetryOutcome.STARTED, deciding.retry(USER, untouched.fileId))
        assertEquals(DownloadRetryOutcome.STARTED, deciding.retry(USER, held.fileId))
        assertEquals(DownloadRetryOutcome.UNAVAILABLE, deciding.retry(USER, gone.fileId))

        assertEquals(
            listOf(11L to DownloadArtifact.ORIGINAL, 12L to DownloadArtifact.ORIGINAL_HLS),
            sent.map { it.fileId.value to it.artifact },
        )
        assertEquals(listOf("en-key"), sent.first().subtitles.map { it.key })
        assertEquals(listOf(11L, 13L), asked)
        assertEquals(
            listOf(untouched.copy(artifact = DownloadArtifact.ORIGINAL, subtitles = sent.first().subtitles)),
            saved,
        )
        assertEquals(listOf(gone), failed)
        assertTrue(dismissed.isEmpty())
    }

    @Test
    fun retryUnderAnotherAccountOrNoneRetriesNothingAndRemovesTheNotification() = runBlocking {
        rows[USER to 11L] = row(11L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))
        rows[OTHER to 11L] = row(11L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))

        account = SignedInAccount.User(OTHER)
        assertEquals(DownloadRetryOutcome.REFUSED, retries.retry(USER, FilesItemId(11L)))
        account = SignedInAccount.Nobody
        assertEquals(DownloadRetryOutcome.REFUSED, retries.retry(USER, FilesItemId(11L)))

        assertTrue(started.isEmpty())
        assertEquals(listOf(USER to FilesItemId(11L), USER to FilesItemId(11L)), dismissed)
    }

    @Test
    fun aSignOutDuringTheDirectPlayLookupStartsAndSavesNothing() = runBlocking {
        val untouched = row(11L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))
            .copy(artifact = DownloadArtifact.MP4)
        rows[USER to 11L] = untouched
        val sent = mutableListOf<DownloadEntry>()
        val saved = mutableListOf<DownloadEntry>()
        val deciding = DownloadNotificationRetries(
            signedIn = { account },
            find = { userId, fileId -> rows[userId to fileId.value] },
            start = { _, entry -> sent += entry },
            dismiss = { userId, fileId -> dismissed += userId to fileId },
            decision = DownloadRetryDecision(
                directPlay = { _, _ ->
                    account = SignedInAccount.Nobody
                    DirectPlayDownload(DirectPlayDownloadSource.Original, listOf(ENGLISH))
                },
                save = { _, entry -> saved += entry },
            ),
        )

        assertEquals(DownloadRetryOutcome.REFUSED, deciding.retry(USER, untouched.fileId))

        assertTrue(sent.isEmpty())
        assertTrue(saved.isEmpty())
        assertEquals(listOf(USER to untouched.fileId), dismissed)
    }

    @Test
    fun aRowRemovedOrChangedDuringTheDirectPlayLookupIsNotRevived() = runBlocking {
        val removed = row(11L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))
            .copy(artifact = DownloadArtifact.MP4)
        val deleting = row(12L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))
            .copy(artifact = DownloadArtifact.MP4)
        for (entry in listOf(removed, deleting)) rows[USER to entry.fileId.value] = entry
        val sent = mutableListOf<DownloadEntry>()
        val saved = mutableListOf<DownloadEntry>()
        val deciding = DownloadNotificationRetries(
            signedIn = { account },
            find = { userId, fileId -> rows[userId to fileId.value] },
            start = { _, entry -> sent += entry },
            dismiss = { userId, fileId -> dismissed += userId to fileId },
            decision = DownloadRetryDecision(
                directPlay = { fileId, _ ->
                    if (fileId == removed.fileId) {
                        rows.remove(USER to fileId.value)
                    } else {
                        rows[USER to fileId.value] = deleting.copy(removing = true)
                    }
                    DirectPlayDownload(DirectPlayDownloadSource.Original, listOf(ENGLISH))
                },
                save = { _, entry -> saved += entry },
            ),
        )

        assertEquals(DownloadRetryOutcome.NOT_RETRYABLE, deciding.retry(USER, removed.fileId))
        assertEquals(DownloadRetryOutcome.NOT_RETRYABLE, deciding.retry(USER, deleting.fileId))

        assertTrue(sent.isEmpty())
        assertTrue(saved.isEmpty())
        assertEquals(null, rows[USER to removed.fileId.value])
        assertEquals(listOf(USER to removed.fileId, USER to deleting.fileId), dismissed)
    }

    @Test
    fun anUnconfirmedSessionLeavesTheNotificationForLater() = runBlocking {
        rows[USER to 11L] = row(11L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))
        account = SignedInAccount.Unknown

        assertEquals(DownloadRetryOutcome.UNCONFIRMED, retries.retry(USER, FilesItemId(11L)))

        assertTrue(started.isEmpty())
        assertTrue(dismissed.isEmpty())
    }

    @Test
    fun aRowThatIsGoneDeletingOrNoLongerFailedIsNotRetried() = runBlocking {
        rows[USER to 12L] = row(12L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L)).copy(removing = true)
        rows[USER to 13L] = row(13L, DownloadStatus.Queued)

        for (fileId in listOf(12L, 13L, 14L)) {
            assertEquals(DownloadRetryOutcome.NOT_RETRYABLE, retries.retry(USER, FilesItemId(fileId)))
        }

        assertTrue(started.isEmpty())
        assertEquals(listOf(12L, 13L, 14L).map { USER to FilesItemId(it) }, dismissed)
    }

    @Test
    fun aStartTheSystemRefusesKeepsTheNotificationForAnotherTry() = runBlocking {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val failed = row(11L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))
        val sent = mutableListOf<String>()
        var refuse = true
        val withService = DownloadNotificationRetries(
            signedIn = { SignedInAccount.User(USER) },
            find = { _, _ -> failed },
            start = { userId, entry ->
                sendDownloadRequest(context, userId, entry) { request, _ ->
                    // Android 12+ refuses a foreground service start once the tap's allowance has passed.
                    check(!refuse) { "ForegroundServiceStartNotAllowedException" }
                    sent += request.id
                }
            },
            dismiss = { userId, fileId -> MobileDownloadNotifications.cancel(context, userId, fileId) },
        )
        MobileDownloadNotifications.post(context, USER, failed, DownloadOutcome.Failed(DownloadFailureReason.NETWORK))

        assertEquals(DownloadRetryOutcome.UNCONFIRMED, withService.retry(USER, FilesItemId(11L)))
        assertEquals(1, notifications.activeNotifications.size)

        refuse = false
        assertEquals(DownloadRetryOutcome.STARTED, withService.retry(USER, FilesItemId(11L)))
        assertEquals(listOf("$USER:11"), sent)
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test
    fun theNotificationsPendingIntentReachesTheReceiverWithItsAccountAndFile() {
        val received = CompletableDeferred<Pair<Long, FilesItemId>>()
        MobileDownloadActionReceiver.retriesForTest = DownloadNotificationRetries(
            signedIn = { SignedInAccount.User(USER) },
            find = { _, fileId -> row(fileId.value, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L)) },
            start = { userId, entry -> received.complete(userId to entry.fileId) },
            dismiss = { _, _ -> },
        )

        shadowOf(MobileDownloadActionReceiver.retry(context, USER, FilesItemId(11L))).savedIntent
            .let(context::sendBroadcast)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(received.isCompleted)
        assertEquals(USER to FilesItemId(11L), runBlocking { received.await() })
    }

    @Test
    fun pauseAndCancelActOnEveryLiveDownloadOfTheSignedInOwnerOnly() = runBlocking {
        val paused = mutableListOf<Pair<Long, FilesItemId>>()
        val cancelled = mutableListOf<Pair<Long, FilesItemId>>()
        val controls = DownloadNotificationControls(
            signedIn = { account },
            live = { userId -> if (userId == USER) listOf(FilesItemId(10L), FilesItemId(11L)) else emptyList() },
            pause = { userId, fileId -> paused += userId to fileId },
            cancel = { userId, fileId -> cancelled += userId to fileId },
        )

        assertEquals(2, controls.pauseAll(USER))
        assertEquals(listOf(USER to FilesItemId(10L), USER to FilesItemId(11L)), paused)

        // A notification left from another session acts on nothing.
        account = SignedInAccount.User(OTHER)
        assertEquals(0, controls.cancelAll(USER))
        account = SignedInAccount.Unknown
        assertEquals(0, controls.cancelAll(USER))
        assertTrue(cancelled.isEmpty())

        account = SignedInAccount.User(USER)
        assertEquals(2, controls.cancelAll(USER))
        assertEquals(listOf(USER to FilesItemId(10L), USER to FilesItemId(11L)), cancelled)
    }

    @Test
    fun theRunningNotificationsPauseAndCancelReachTheReceiverForTheirAccount() {
        val paused = CompletableDeferred<Long>()
        val cancelled = CompletableDeferred<Long>()
        MobileDownloadActionReceiver.controlsForTest = DownloadNotificationControls(
            signedIn = { SignedInAccount.User(USER) },
            live = { listOf(FilesItemId(10L)) },
            pause = { userId, _ -> paused.complete(userId) },
            cancel = { userId, _ -> cancelled.complete(userId) },
        )

        val pause = shadowOf(MobileDownloadActionReceiver.pauseAll(context, USER))
        val cancel = shadowOf(MobileDownloadActionReceiver.cancelAll(context, USER))
        // Explicit and immutable broadcasts to the unexported receiver; nothing opens.
        assertTrue(pause.isBroadcast && pause.isImmutable && cancel.isBroadcast && cancel.isImmutable)
        context.sendBroadcast(pause.savedIntent)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(paused.isCompleted && !cancelled.isCompleted)
        context.sendBroadcast(cancel.savedIntent)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(USER, runBlocking { paused.await() })
        assertEquals(USER, runBlocking { cancelled.await() })
    }

    @Test
    fun theNotificationAndTheScreenShareOneStorePerAccount() {
        // A pause written from the notification is the row the engine reads, so it never undoes it.
        assertTrue(MobileDownloadStore.shared(context, USER) === MobileDownloadStore.shared(context, USER))
        assertTrue(MobileDownloadStore.shared(context, USER) !== MobileDownloadStore.shared(context, OTHER))
    }

    private fun row(fileId: Long, status: DownloadStatus) = DownloadEntry(
        FilesItemId(fileId), "file-$fileId.mkv", PutioFileType.VIDEO, DownloadArtifact.HLS, status,
        createdAt = fileId, accepted = true,
    )

    private companion object {
        const val USER = 7L
        const val OTHER = 8L
        val ENGLISH = FileSubtitle(
            format = "srt",
            key = "en-key",
            language = "English",
            languageCode = "eng",
            name = "Sintel.en.srt",
            source = "opensubtitles",
            url = "https://api.put.io/v2/files/11/subtitles/en-key",
        )
    }
}
