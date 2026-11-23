package io.putdotio.android.share

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MediaDownloadToken
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountDownloadToken
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class MobileFileShareServiceTest {
    private val activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    @After
    fun reset() {
        MobileResumedActivity.paused(activity)
        MobileFileShareService.dependenciesForTest = null
    }

    @Test
    fun chooserCarriesOnlyAContentStreamWithAReadGrant() {
        val authority = "${ApplicationProvider.getApplicationContext<Context>().packageName}.share"
        val uri = "content://$authority/shares/9/Sintel.mp4".toUri()
        val chooser = shareChooserFor(uri, "video/mp4")
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("video/mp4", send.type)
        val stream = requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertEquals("content", stream.scheme)
        assertEquals(authority, stream.authority)
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        val everything = listOf(chooser, send)
            .joinToString { it.toUri(0) + it.extras?.keySet()?.joinToString().orEmpty() }
        assertFalse(everything.contains("oauth_token"))
        assertFalse(everything.contains("http"))
        assertEquals(stream, send.clipData?.getItemAt(0)?.uri)
        // createChooser migrates the grant onto the wrapper; the launched intent is what carries it.
        assertEquals(stream, chooser.clipData?.getItemAt(0)?.uri)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val untyped = shareChooserFor(uri, null).getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals("application/octet-stream", untyped?.type)
    }

    @Test
    fun theFileProviderSharesOnlyTheExportDirectoryAndNeverWritesOrExports() {
        val service = Robolectric.buildService(MobileFileShareService::class.java).create().get()
        val provider = service.packageManager.getProviderInfo(
            ComponentName(service, "androidx.core.content.FileProvider"),
            PackageManager.GET_META_DATA,
        )
        assertEquals("${service.packageName}.share", provider.authority)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val paths = service.resources.getXml(
            provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS"),
        )
        val roots = buildList {
            while (paths.next() != XmlPullParser.END_DOCUMENT) {
                if (paths.eventType == XmlPullParser.START_TAG && paths.name != "paths") {
                    val (name, path) = listOf("name", "path").map { paths.getAttributeValue(null, it) }
                    add(Triple(paths.name, name, path))
                }
            }
        }
        assertEquals(listOf(Triple("files-path", "shares", "shares/")), roots)
        assertEquals("shares", MobileFileShareService.shareRoot(service).name)
        assertEquals(service.filesDir, MobileFileShareService.shareRoot(service).parentFile)
    }

    @Test
    fun deliveryUsesTheResumedActivityOrWaitsForTheNextOne() {
        val fixture = ShareFixture()
        val service = fixture.service
        // FileProvider caches its root per authority across tests, so build the payload directly here.
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, "content://${service.packageName}.share/shares/9/poster.jpg".toUri()),
            null,
        )

        MobileResumedActivity.resumed(activity)
        fixture.launch { service.deliverForTest(chooser, "poster.jpg", SESSION) }
        fixture.scheduler.runCurrent()
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(activity).nextStartedActivity?.action)
        MobileResumedActivity.paused(activity)

        val pending = fixture.launch { service.deliverForTest(chooser, "poster.jpg", SESSION) }
        fixture.scheduler.runCurrent()
        assertFalse(pending.isCompleted)
        val manager = service.getSystemService(NotificationManager::class.java)
        val ready = requireNotNull(shadowOf(manager).getNotification(MobileFileShareService.NOTIFICATION_ID))
        val shown = shadowOf(ready).contentText.toString() +
            (ready.contentIntent?.let { shadowOf(it).savedIntent?.toUri(0) } ?: "")
        assertFalse(shown.contains("oauth_token"))
        assertFalse(shown.contains("http"))
        assertNull(shadowOf(activity).nextStartedActivity)

        MobileResumedActivity.resumed(activity)
        fixture.scheduler.runCurrent()
        assertTrue(pending.isCompleted)
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(activity).nextStartedActivity?.action)
        assertTrue(shadowOf(service).isForegroundStopped)
        MobileResumedActivity.paused(activity)

        val export = fixture.writeExport("9/poster.jpg")
        val waiting = fixture.launch { service.deliverForTest(chooser, "poster.jpg", SESSION) }
        fixture.scheduler.runCurrent()
        waiting.cancel()
        fixture.scheduler.runCurrent()
        assertFalse(export.exists())
        fixture.destroy()
    }

    @Test
    fun deliveryReChecksTheSessionBeforeOpeningTheChooser() {
        val fixture = ShareFixture()
        // The download completes after the session ended, before anything observed it; the resume must not share.
        fixture.respond = { request ->
            fixture.auth.value = MobileAuthState.SigningOut
            ok(request)
        }
        MobileResumedActivity.resumed(activity)
        fixture.start(fileId = 9L, name = "poster.jpg", startId = 1)
        fixture.scheduler.advanceUntilIdle()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(MobileFileShareService.shareRoot(fixture.service).exists())
        assertTrue(shadowOf(fixture.service).isForegroundStopped)
        assertEquals(1, shadowOf(fixture.service).stopSelfId)
        fixture.destroy()
    }

    @Test
    fun leavingTheSessionWhileWaitingDropsTheExportAndNeverOpensTheChooser() {
        val fixture = ShareFixture()
        fixture.start(fileId = 9L, name = "poster.jpg", startId = 1)
        fixture.scheduler.runCurrent()
        assertTrue(fixture.export("9/poster.jpg").exists())

        fixture.auth.value = MobileAuthState.SignedOut()
        fixture.scheduler.runCurrent()
        assertFalse(MobileFileShareService.shareRoot(fixture.service).exists())
        assertTrue(shadowOf(fixture.service).isForegroundStopped)
        assertEquals(1, shadowOf(fixture.service).stopSelfId)

        // Signing in again, as a new session, does not revive the dropped export.
        fixture.auth.value = signedIn(MobileAuthSessionId(2L))
        MobileResumedActivity.resumed(activity)
        fixture.scheduler.advanceUntilIdle()
        assertNull(shadowOf(activity).nextStartedActivity)
        fixture.destroy()
    }

    @Test
    fun anExportNobodyReturnsForIsDeletedAfterTheTimeout() {
        val fixture = ShareFixture(readyTimeout = 1.minutes)
        fixture.start(fileId = 9L, name = "poster.jpg", startId = 1)
        fixture.scheduler.advanceTimeBy(59.seconds)
        fixture.scheduler.runCurrent()
        assertTrue(fixture.export("9/poster.jpg").exists())
        assertFalse(shadowOf(fixture.service).isForegroundStopped)

        fixture.scheduler.advanceTimeBy(2.seconds)
        fixture.scheduler.runCurrent()
        assertFalse(MobileFileShareService.shareRoot(fixture.service).exists())
        assertTrue(shadowOf(fixture.service).isForegroundStopped)
        assertEquals(1, shadowOf(fixture.service).stopSelfId)
        MobileResumedActivity.resumed(activity)
        fixture.scheduler.advanceUntilIdle()
        assertNull(shadowOf(activity).nextStartedActivity)
        fixture.destroy()
    }

    @Test
    fun anExportRemovesEveryEarlierExportFirst() {
        val fixture = ShareFixture()
        val earlier = fixture.writeExport("7/old.bin")
        fixture.start(fileId = 9L, name = "poster.jpg", startId = 1)
        fixture.scheduler.runCurrent()
        assertFalse(earlier.exists())
        assertFalse(fixture.export("7").exists())
        assertEquals("poster", fixture.export("9/poster.jpg").readText())
        fixture.destroy()
    }

    @Test
    fun aFailedOrUnreachableDownloadLeavesAFailureNotificationAndNoExport() {
        val serverError: (Request) -> Response = { request ->
            ok(request).newBuilder().code(500).message("Server Error").build()
        }
        val offline: (Request) -> Response = { throw IOException("offline") }
        for (respond in listOf(serverError, offline)) {
            val fixture = ShareFixture()
            fixture.respond = respond
            fixture.start(fileId = 9L, name = "poster.jpg", startId = 1)
            fixture.scheduler.advanceUntilIdle()
            val manager = fixture.service.getSystemService(NotificationManager::class.java)
            val notification = requireNotNull(
                shadowOf(manager).getNotification(MobileFileShareService.NOTIFICATION_ID),
            )
            assertEquals(FAILED_TEXT, shadowOf(notification).contentText.toString())
            // Detached, so the failure stays visible after the service stops.
            assertTrue(shadowOf(fixture.service).isForegroundStopped)
            assertFalse(shadowOf(fixture.service).notificationShouldRemoved)
            assertFalse(fixture.export("9").exists())
            assertEquals(1, shadowOf(fixture.service).stopSelfId)
            fixture.destroy()
        }
    }

    @Test
    fun cancelJoinsTheRunningExportBeforeStopping() {
        val fixture = ShareFixture()
        fixture.start(fileId = 1L, name = "a", startId = 1)
        fixture.service.onStartCommand(
            Intent(fixture.service, MobileFileShareService::class.java)
                .setAction("io.putdotio.android.action.CANCEL_SHARE"),
            0,
            2,
        )
        fixture.scheduler.advanceUntilIdle()
        assertEquals(2, shadowOf(fixture.service).stopSelfId)
        assertTrue(shadowOf(fixture.service).isForegroundStopped)
        assertFalse(fixture.export("1").exists())
        fixture.destroy()
    }

    @Test
    fun theDataSyncTimeoutDropsTheExportAndStopsTheServiceWithAFailureNotice() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val fixture = ShareFixture()
        fixture.start(fileId = 9L, name = "poster.jpg", startId = 1)
        fixture.scheduler.runCurrent()
        assertTrue(fixture.export("9/poster.jpg").exists())
        assertFalse(shadowOf(fixture.service).isForegroundStopped)

        fixture.service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        fixture.scheduler.advanceUntilIdle()

        assertTrue(shadowOf(fixture.service).isForegroundStopped)
        assertTrue(shadowOf(fixture.service).notificationShouldRemoved)
        assertTrue(shadowOf(fixture.service).isStoppedBySelf)
        assertFalse(MobileFileShareService.shareRoot(fixture.service).exists())
        val manager = fixture.service.getSystemService(NotificationManager::class.java)
        val notice = requireNotNull(shadowOf(manager).allNotifications.singleOrNull())
        assertEquals(FAILED_TEXT, shadowOf(notice).contentText.toString())
        MobileResumedActivity.resumed(activity)
        fixture.scheduler.advanceUntilIdle()
        assertNull(shadowOf(activity).nextStartedActivity)
        fixture.destroy()
    }

    @Test
    fun endingTheSessionCancelsADownloadBlockedOnTheNetwork() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        // The download blocks a real IO thread while virtual time drives the session watcher.
        val fixture = ShareFixture(io = Dispatchers.IO, onCallCanceled = cancelled::countDown)
        fixture.respond = {
            entered.countDown()
            cancelled.await(BLOCKED_CALL_SECONDS, TimeUnit.SECONDS)
            throw IOException("Canceled")
        }
        fixture.start(fileId = 1L, name = "a", startId = 1)
        fixture.scheduler.runCurrent()
        assertTrue(entered.await(BLOCKED_CALL_SECONDS, TimeUnit.SECONDS))

        fixture.auth.value = MobileAuthState.SignedOut()
        fixture.scheduler.runCurrent()

        assertTrue(cancelled.await(BLOCKED_CALL_SECONDS, TimeUnit.SECONDS))
        fixture.destroy()
    }

    @Test
    fun endingASessionDeletesOnlyTheExportsItHandedOut() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun exportOf(session: MobileAuthSessionId) =
            File(MobileFileShareService.sessionShares(context, session), "9/poster.jpg").apply {
                parentFile?.mkdirs()
                writeText("bytes")
            }
        fun earlierProcessExport() = File(MobileFileShareService.shareRoot(context), "earlier/1/9/poster.jpg").apply {
            parentFile?.mkdirs()
            writeText("bytes")
        }
        val ended = exportOf(SESSION)
        val next = exportOf(MobileAuthSessionId(2L))
        val leftover = earlierProcessExport()

        MobileFileShareService.endSession(context, SESSION)
        assertFalse(ended.exists())
        assertFalse(leftover.exists())
        assertTrue(next.exists())

        // A session an earlier process left has no known id; ending it clears every earlier-process leftover.
        val restoredLeftover = earlierProcessExport()
        MobileFileShareService.endSession(context, null)
        assertFalse(restoredLeftover.exists())
        assertTrue(next.exists())
    }

    @Test
    fun exportedNamesAreSinglePathSegments() {
        assertEquals("a_b_c.mkv", "a/b c.mkv".sanitizedFileName())
        assertEquals("a_b.mkv", "a\\b.mkv".sanitizedFileName())
        assertEquals("a_b_.txt", "a\tb\n.txt".sanitizedFileName())
        assertEquals("a_b.mkv", "a b.mkv".sanitizedFileName())
        assertEquals("file", "..".sanitizedFileName())
        assertEquals("file", "   ".sanitizedFileName())
    }

    @Test
    fun exportedNamesDropControlAndBidiCharacters() {
        // U+202E would render "invoice_‮gnp.exe" as "invoice_exe.png".
        assertEquals("invoice_gnp.exe", "invoice_‮gnp.exe".sanitizedFileName())
        assertEquals("ab.mkv", "a\u0000\u0007\u0085b​‎‏⁦⁩؜﻿.mkv".sanitizedFileName())
        assertEquals("ab.mkv", "a\uD800b.mkv".sanitizedFileName())
        assertEquals("file", "‮​".sanitizedFileName())
        assertEquals("file", ".​.".sanitizedFileName())
    }

    @Test
    fun exportedNamesFitTheFileSystemInUtf8Bytes() {
        assertEquals("x".repeat(200), "x".repeat(300).sanitizedFileName())
        // Three bytes per character: 200 characters would be 600 bytes, over the 255-byte name limit.
        assertEquals("字".repeat(65) + ".mkv", ("字".repeat(150) + ".mkv").sanitizedFileName())
        // Four-byte code points are never split into a lone surrogate.
        assertEquals("🎬".repeat(50), "🎬".repeat(80).sanitizedFileName())
    }

    @Test
    fun launchPruneKeepsRecentExportsAndDropsStaleOnes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = MobileFileShareService.shareRoot(context)
        val stale = File(root, "1").apply { mkdirs(); File(this, "a.bin").writeText("a") }
        val recent = File(root, "2").apply { mkdirs(); File(this, "b.bin").writeText("b") }
        val now = System.currentTimeMillis()
        stale.setLastModified(now - 2L * 24L * 60L * 60L * 1000L)
        recent.setLastModified(now)
        MobileFileShareService.pruneStale(context, now)
        assertFalse(stale.exists())
        assertTrue(File(recent, "b.bin").exists())
    }

    @Test
    fun invalidStartStopsOnlyItsOwnStartId() {
        val fixture = ShareFixture()
        fixture.service.onStartCommand(Intent(fixture.service, MobileFileShareService::class.java), 0, 7)
        fixture.scheduler.advanceUntilIdle()
        assertTrue(shadowOf(fixture.service).isForegroundStopped)
        assertEquals(7, shadowOf(fixture.service).stopSelfId)
        fixture.destroy()
    }

    @Test
    fun secondStartDoesNotStopTheServiceWhenTheFirstJobEnds() {
        val fixture = ShareFixture()
        fixture.auth.value = MobileAuthState.SignedOut()
        fixture.start(fileId = 1L, name = "a", startId = 1)
        fixture.start(fileId = 2L, name = "b", startId = 2)
        // Without a session both exports fail. Each job stops only its own startId, so the framework
        // keeps the service alive until the newest start reports; the last stop must carry 2.
        fixture.scheduler.advanceUntilIdle()
        assertEquals(2, shadowOf(fixture.service).stopSelfId)
        fixture.destroy()
    }

    @Test
    fun shareNotificationsHideFilenamesOnLockScreenAndAlertOnce() {
        val notifications = MobileShareNotifications(ApplicationProvider.getApplicationContext())
        val states = listOf(
            notifications.progress("Secret.Movie.mkv", indeterminate = false, progress = 50) to
                "Preparing Secret.Movie.mkv",
            notifications.ready("Secret.Movie.mkv") to
                "Secret.Movie.mkv is ready to share",
            notifications.failed("Secret.Movie.mkv") to
                "Couldn’t prepare Secret.Movie.mkv. Try again.",
        )
        for ((notification, expectedText) in states) {
            assertEquals(
                expectedText,
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
            )
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            val publicVersion = requireNotNull(notification.publicVersion)
            assertEquals("Share file", publicVersion.extras.getString(Notification.EXTRA_TITLE))
            assertNull(publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertNull(publicVersion.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            assertTrue(publicVersion.actions.isNullOrEmpty())
            assertNull(publicVersion.contentIntent)
        }
    }

    @Test
    fun startAndCancelSurviveBackgroundServiceRestrictions() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val stoppedServices = mutableListOf<Intent>()
        val restricted = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent?): ComponentName? {
                error("FGS disallowed")
            }

            override fun startService(service: Intent?): ComponentName? {
                error("BG disallowed")
            }

            override fun stopService(name: Intent?): Boolean {
                if (name != null) stoppedServices += name
                return true
            }
        }
        MobileFileShareService.start(restricted, FilesItemId(9L), "poster.jpg")
        val manager = app.getSystemService(NotificationManager::class.java)
        val notice = requireNotNull(shadowOf(manager).getNotification(MobileFileShareService.NOTIFICATION_ID))
        assertEquals(FAILED_TEXT, shadowOf(notice).contentText.toString())

        MobileFileShareService.cancel(restricted)
        assertEquals(MobileFileShareService.ACTION_CANCEL, stoppedServices.single().action)
    }

    @Test
    fun cancelUsesStartServiceAndNeverPromotesAForegroundService() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val foregroundStarts = mutableListOf<Intent>()
        val serviceStarts = mutableListOf<Intent>()
        val recording = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent?): ComponentName? {
                if (service != null) foregroundStarts += service
                return null
            }

            override fun startService(service: Intent?): ComponentName? {
                if (service != null) serviceStarts += service
                return null
            }
        }
        MobileFileShareService.cancel(recording)
        assertTrue(foregroundStarts.isEmpty())
        assertEquals(MobileFileShareService.ACTION_CANCEL, serviceStarts.single().action)

        val fixture = ShareFixture()
        fixture.service.onStartCommand(
            Intent(fixture.service, MobileFileShareService::class.java)
                .setAction(MobileFileShareService.ACTION_CANCEL),
            0,
            5,
        )
        fixture.scheduler.advanceUntilIdle()
        assertNull(shadowOf(fixture.service).lastForegroundNotification)
        assertEquals(5, shadowOf(fixture.service).stopSelfId)
        fixture.destroy()
    }

    @Test
    fun progressUpdatesUseNotificationManagerOnMainAndCannotRaceOnTimeout() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val stream = GatedProgressStream()
        val fixture = ShareFixture(io = Dispatchers.IO, onCallCanceled = stream::releaseAll)
        fixture.respond = { request -> gatedResponse(request, stream) }
        try {
            fixture.start(fileId = 9L, name = "poster.jpg", startId = 1)
            fixture.scheduler.runCurrent()
            assertTrue(stream.firstChunkDelivered.await(BLOCKED_CALL_SECONDS, TimeUnit.SECONDS))
            fixture.scheduler.runCurrent()

            val fg = requireNotNull(shadowOf(fixture.service).lastForegroundNotification)
            assertTrue(fg.extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE))
            val manager = fixture.service.getSystemService(NotificationManager::class.java)
            val progress = requireNotNull(shadowOf(manager).getNotification(MobileFileShareService.NOTIFICATION_ID))
            assertEquals(HALF_PROGRESS, progress.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
            assertFalse(progress.extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE))

            stream.allowSecondChunk.countDown()
            assertTrue(stream.secondChunkDelivered.await(BLOCKED_CALL_SECONDS, TimeUnit.SECONDS))
            fixture.service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            stream.allowClose.countDown()
            fixture.scheduler.advanceUntilIdle()

            assertTrue(shadowOf(fixture.service).isForegroundStopped)
            assertNull(shadowOf(fixture.service).lastForegroundNotification)
            val finalNotice = requireNotNull(shadowOf(manager).getNotification(MobileFileShareService.NOTIFICATION_ID))
            assertEquals(FAILED_TEXT, shadowOf(finalNotice).contentText.toString())
            assertNull(MobileShareProgress.state.value)
        } finally {
            stream.releaseAll()
            fixture.destroy()
        }
    }

    private fun gatedResponse(request: Request, stream: GatedProgressStream): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength(): Long = CHUNK_BYTES * 2L
                    override fun source(): BufferedSource = stream.source().buffer()
                },
            )
            .build()

    private class GatedProgressStream : InputStream() {
        val firstChunkDelivered = CountDownLatch(1)
        val allowSecondChunk = CountDownLatch(1)
        val secondChunkDelivered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        private var stage = 0

        fun releaseAll() {
            allowSecondChunk.countDown()
            allowClose.countDown()
        }

        override fun read(): Int = ByteArray(1).let { buf -> if (read(buf, 0, 1) < 0) -1 else buf[0].toInt() and 0xFF }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            when (stage) {
                0 -> {
                    stage = 1
                    val count = minOf(len, CHUNK_BYTES)
                    b.fill(0x41, off, off + count)
                    count
                }
                1 -> {
                    stage = 2
                    firstChunkDelivered.countDown()
                    allowSecondChunk.await(BLOCKED_CALL_SECONDS, TimeUnit.SECONDS)
                    val count = minOf(len, CHUNK_BYTES)
                    b.fill(0x42, off, off + count)
                    count
                }
                else -> {
                    secondChunkDelivered.countDown()
                    allowClose.await(BLOCKED_CALL_SECONDS, TimeUnit.SECONDS)
                    -1
                }
            }
    }

    private class ShareFixture(
        readyTimeout: Duration = 10.minutes,
        io: CoroutineDispatcher? = null,
        onCallCanceled: () -> Unit = {},
    ) {
        val scheduler = TestCoroutineScheduler()
        private val dispatcher = StandardTestDispatcher(scheduler)
        val auth = MutableStateFlow<MobileAuthState>(signedIn(SESSION))
        var respond: (Request) -> Response = ::ok
        private val controller = Robolectric.buildService(MobileFileShareService::class.java).create()
        val service: MobileFileShareService = controller.get()

        init {
            MobileFileShareService.dependenciesForTest = MobileShareDependencies(
                http = OkHttpClient.Builder()
                    .addInterceptor { chain -> respond(chain.request()) }
                    .eventListener(
                        object : EventListener() {
                            override fun canceled(call: Call) = onCallCanceled()
                        },
                    )
                    .build(),
                authState = auth,
                originals = OriginalDownloads(
                    MediaDownloadToken { AccountDownloadToken("token") },
                    PutioClient().files,
                ),
                readyTimeout = readyTimeout,
                main = dispatcher,
                io = io ?: dispatcher,
            )
            MobileFileShareService.shareRoot(service).deleteRecursively()
        }

        fun start(fileId: Long, name: String, startId: Int) {
            val intent = Intent(service, MobileFileShareService::class.java)
                .putExtra("fileId", fileId)
                .putExtra("name", name)
            service.onStartCommand(intent, 0, startId)
        }

        fun launch(block: suspend () -> Unit): Deferred<Unit> = CoroutineScope(dispatcher).async { block() }

        fun export(path: String): File = File(MobileFileShareService.sessionShares(service, SESSION), path)

        fun writeExport(path: String): File = export(path).apply {
            parentFile?.mkdirs()
            writeText("bytes")
        }

        fun destroy() {
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private companion object {
        const val BLOCKED_CALL_SECONDS = 5L
        const val CHUNK_BYTES = 8_192
        const val HALF_PROGRESS = 50
        const val FAILED_TEXT = "Couldn’t prepare poster.jpg. Try again."
        val SESSION = MobileAuthSessionId(1L)

        fun signedIn(session: MobileAuthSessionId) = MobileAuthState.SignedIn(
            account = MobileAccount(userId = 1L, username = "someone", email = "someone@example.com"),
            sessionId = session,
        )

        fun ok(request: Request): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("poster".toResponseBody())
            .build()
    }
}
