package io.putdotio.android.downloads

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
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

/** A failed download notification's Retry, for its own account only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileDownloadActionReceiverTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
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
    }

    @Test
    fun retryQueuesTheOwnersFailedDownloadAgain() = runBlocking {
        rows[USER to 11L] = row(11L, DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))

        assertEquals(DownloadRetryOutcome.STARTED, retries.retry(USER, FilesItemId(11L)))

        assertEquals(listOf(USER to FilesItemId(11L)), started)
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

    private fun row(fileId: Long, status: DownloadStatus) = DownloadEntry(
        FilesItemId(fileId), "file-$fileId.mkv", PutioFileType.VIDEO, DownloadArtifact.HLS, status,
        createdAt = fileId, accepted = true,
    )

    private companion object {
        const val USER = 7L
        const val OTHER = 8L
    }
}
