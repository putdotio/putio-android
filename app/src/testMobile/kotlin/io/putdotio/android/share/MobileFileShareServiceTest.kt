package io.putdotio.android.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.files.FilesItemId
import java.io.File
import java.io.IOException
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
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
        val service = Robolectric.buildService(MobileFileShareService::class.java).create().get()
        val file = File(MobileFileShareService.shareRoot(service), "9/Sintel.mp4").apply {
            parentFile?.mkdirs()
            writeText("bytes")
        }
        val chooser = service.chooserForTest(file)
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        val stream = requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertEquals("content", stream.scheme)
        assertEquals("${service.packageName}.share", stream.authority)
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        val everything = listOf(chooser, send).joinToString { it.toUri(0) + it.extras?.keySet()?.joinToString().orEmpty() }
        assertFalse(everything.contains("oauth_token"))
        assertFalse(everything.contains("http"))
        assertEquals(stream, send.clipData?.getItemAt(0)?.uri)
        // createChooser migrates the grant onto the wrapper; the launched intent is what carries it.
        assertEquals(stream, chooser.clipData?.getItemAt(0)?.uri)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
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
        val ready = requireNotNull(shadowOf(service).lastForegroundNotification)
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
    fun aFailedDownloadLeavesAFailureNotificationAndNoExport() {
        val fixture = ShareFixture()
        fixture.respond = { request -> ok(request).newBuilder().code(500).message("Server Error").build() }
        fixture.start(fileId = 9L, name = "poster.jpg", startId = 1)
        fixture.scheduler.advanceUntilIdle()
        val notification = requireNotNull(shadowOf(fixture.service).lastForegroundNotification)
        assertEquals(FAILED_TEXT, shadowOf(notification).contentText.toString())
        // Detached, so the failure stays visible after the service stops.
        assertTrue(shadowOf(fixture.service).isForegroundStopped)
        assertFalse(shadowOf(fixture.service).notificationShouldRemoved)
        assertFalse(fixture.export("9").exists())
        assertEquals(1, shadowOf(fixture.service).stopSelfId)
        fixture.destroy()
    }

    @Test
    fun aNetworkErrorAlsoReportsFailure() {
        val fixture = ShareFixture()
        fixture.respond = { throw IOException("offline") }
        fixture.start(fileId = 9L, name = "poster.jpg", startId = 1)
        fixture.scheduler.advanceUntilIdle()
        val notification = requireNotNull(shadowOf(fixture.service).lastForegroundNotification)
        assertEquals(FAILED_TEXT, shadowOf(notification).contentText.toString())
        fixture.destroy()
    }

    @Test
    fun downloadRequestKeepsTheTokenInTheHeaderOnly() {
        val request = MobileFileShareService.downloadRequest(FilesItemId(9L), "secret-token")
        assertEquals("https://api.put.io/v2/files/9/download", request.url.toString())
        assertEquals("Token secret-token", request.header("Authorization"))
        assertFalse(request.url.toString().contains("secret-token"))
    }

    @Test
    fun cancelJoinsTheRunningExportBeforeStopping() {
        val fixture = ShareFixture()
        fixture.start(fileId = 1L, name = "a", startId = 1)
        fixture.service.onStartCommand(
            Intent(fixture.service, MobileFileShareService::class.java).setAction("io.putdotio.android.action.CANCEL_SHARE"),
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
        val ended = exportOf(SESSION)
        val next = exportOf(MobileAuthSessionId(2L))

        MobileFileShareService.endSession(context, SESSION)
        assertFalse(ended.exists())
        assertTrue(next.exists())

        // A session an earlier process left has no known id; ending it clears every leftover.
        MobileFileShareService.endSession(context, null)
        assertFalse(MobileFileShareService.shareRoot(context).exists())
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
                accessToken = { "token" },
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
