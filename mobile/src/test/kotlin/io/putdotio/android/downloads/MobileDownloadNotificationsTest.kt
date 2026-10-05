package io.putdotio.android.downloads

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.MainActivity
import io.putdotio.android.MobileDeepLink
import io.putdotio.android.parseMobileDeepLink
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Outcomes from Media3's listener, against Robolectric's notification manager and permission state. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileDownloadNotificationsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var signedIn: Long? = USER
    private val notifications = MobileDownloadNotifications(context) { signedIn }
    // The listener never reads the manager; Media3 hands it one anyway.
    private val downloadManager = DownloadManager(
        context,
        DefaultDownloadIndex(StandaloneDatabaseProvider(context), "NotificationsTest"),
    ) { error("No transfer runs here") }

    @Before
    fun seedRows() = runBlocking {
        val store = MobileDownloadStore(context, USER)
        store.upsert(row(10L, "Sintel.mkv"))
        store.upsert(row(11L, "Tears.mkv"))
        store.upsert(row(12L, "Leaving.mkv").copy(removing = true))
    }

    @After
    fun clearRows() {
        downloadManager.release()
        downloadPreferences(context).edit().clear().commit()
    }

    @Test
    fun aGrantedAppHearsWhenADownloadFinishesOrFailsAndTheTapOpensThatRow() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.changed(download(10L, Download.STATE_COMPLETED))
        notifications.changed(download(11L, Download.STATE_FAILED), UnknownHostException("offline"))

        val posted = shadowOf(manager).allNotifications
        assertEquals(2, posted.size)
        val finished = posted.single { it.extras.getString(Notification.EXTRA_TITLE) == "Download finished" }
        assertEquals("Sintel.mkv", finished.extras.getString(Notification.EXTRA_TEXT))
        // The lock screen sees the outcome only.
        assertEquals(Notification.VISIBILITY_PRIVATE, finished.visibility)
        assertEquals(null, finished.publicVersion.extras.getString(Notification.EXTRA_TEXT))
        val opened = shadowOf(finished.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(Uri.parse("putio://downloads/10?user=$USER"), opened.data)
        assertEquals(MainActivity::class.java.name, opened.component?.className)

        val failed = posted.single { it.extras.getString(Notification.EXTRA_TITLE) == "Download failed" }
        assertTrue(failed.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Tears.mkv"))
        assertTrue(failed.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Retry"))
        assertEquals(Uri.parse("putio://downloads/11?user=$USER"), shadowOf(failed.contentIntent).savedIntent.data)
    }

    @Test
    fun aFinishedDownloadOffersPlayAndAFailedOneRetryEachForItsOwnAccountOnly() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.changed(download(10L, Download.STATE_COMPLETED))
        notifications.changed(download(11L, Download.STATE_FAILED), UnknownHostException("offline"))

        val posted = shadowOf(manager).allNotifications
        val finished = posted.single { it.extras.getString(Notification.EXTRA_TITLE) == "Download finished" }
        assertEquals(listOf("Play", "Open in Downloads"), finished.actions.map { it.title.toString() })
        val play = shadowOf(finished.actions[0].actionIntent)
        assertTrue(play.isActivity && play.isImmutable)
        assertEquals(ComponentName(context, MainActivity::class.java), play.savedIntent.component)
        assertEquals(Uri.parse("putio://downloads/10/play?user=$USER"), play.savedIntent.data)
        assertEquals(
            MobileDeepLink.Downloads(FilesItemId(10L), play = true, userId = USER),
            parseMobileDeepLink(play.savedIntent.data),
        )
        val open = shadowOf(finished.actions[1].actionIntent)
        assertTrue(open.isActivity && open.isImmutable)
        assertEquals(Uri.parse("putio://downloads/10?user=$USER"), open.savedIntent.data)

        val failed = posted.single { it.extras.getString(Notification.EXTRA_TITLE) == "Download failed" }
        assertEquals(listOf("Try again", "Open in Downloads"), failed.actions.map { it.title.toString() })
        val retry = shadowOf(failed.actions[0].actionIntent)
        // A broadcast to the app's own unexported receiver: nothing opens and no other app can send it.
        assertTrue(retry.isBroadcast && retry.isImmutable)
        assertEquals(ComponentName(context, MobileDownloadActionReceiver::class.java), retry.savedIntent.component)
        assertEquals(MobileDownloadActionReceiver.ACTION_RETRY, retry.savedIntent.action)
        assertEquals(Uri.parse("putio://downloads/11?user=$USER"), retry.savedIntent.data)
        assertTrue(failed.actions.none { it.title.toString() == "Play" })
        // No token or URL of the file reaches any intent.
        for (action in finished.actions + failed.actions) {
            assertTrue(shadowOf(action.actionIntent).savedIntent.extras?.isEmpty != false)
        }
    }

    @Test
    fun aSessionEndRemovesEveryOtherAccountsOutcomes() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.changed(download(10L, Download.STATE_COMPLETED))
        runBlocking { MobileDownloadStore(context, OTHER).upsert(row(20L, "Other.mkv")) }
        signedIn = OTHER
        notifications.changed(download(20L, Download.STATE_COMPLETED, user = OTHER))
        assertEquals(2, shadowOf(manager).allNotifications.size)

        // OTHER is already signed in when the collector reports USER's session as ended.
        MobileDownloadNotifications.cancelOtherAccounts(context, signedInUser = OTHER)
        assertEquals(listOf("Other.mkv"), shadowOf(manager).allNotifications.map {
            it.extras.getString(Notification.EXTRA_TEXT)
        })

        MobileDownloadNotifications.cancelOtherAccounts(context, signedInUser = null)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun aDeniedPermissionPostsNothingAndThrowsNothing() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.changed(download(10L, Download.STATE_COMPLETED))
        notifications.changed(download(11L, Download.STATE_FAILED), IOException("write failed: ENOSPC"))

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(false, MobileDownloadNotifications.canPost(context))
    }

    @Test
    fun notificationsTurnedOffForTheAppPostNothing() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)

        notifications.changed(download(10L, Download.STATE_COMPLETED))

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(false, MobileDownloadNotifications.canPost(context))
    }

    @Test
    fun onlyTheSignedInAccountHearsAboutItsDownloads() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // A finished download of an account that is not the one signed in now stays quiet.
        signedIn = OTHER
        notifications.changed(download(10L, Download.STATE_COMPLETED))
        signedIn = null
        notifications.changed(download(11L, Download.STATE_FAILED))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        signedIn = USER
        notifications.changed(download(10L, Download.STATE_COMPLETED))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun deletesRunningStatesAndUnknownRowsStayQuietAndARetryClearsTheOldOutcome() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifications.changed(download(12L, Download.STATE_COMPLETED))
        notifications.changed(download(10L, Download.STATE_DOWNLOADING))
        notifications.changed(download(99L, Download.STATE_COMPLETED))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        notifications.changed(download(11L, Download.STATE_FAILED))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        MobileDownloadNotifications.cancel(context, USER, FilesItemId(11L))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    private fun MobileDownloadNotifications.changed(download: Download, error: Exception? = null) =
        onDownloadChanged(downloadManager, download, error)

    private fun row(fileId: Long, name: String) = DownloadEntry(
        FilesItemId(fileId), name, PutioFileType.VIDEO, DownloadArtifact.HLS, DownloadStatus.Queued,
        createdAt = fileId, accepted = true,
    )

    private fun download(fileId: Long, state: Int, user: Long = USER) = Download(
        DownloadRequest.Builder("$user:$fileId", Uri.parse("https://api.put.io/v2/files/$fileId/stream")).build(),
        state,
        0L,
        0L,
        0L,
        Download.STOP_REASON_NONE,
        if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE,
        DownloadProgress(),
    )

    private companion object {
        const val USER = 7L
        const val OTHER = 8L
    }
}
