package io.putdotio.android

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.downloads.DownloadArtifact
import io.putdotio.android.downloads.DownloadEntry
import io.putdotio.android.downloads.DownloadFailureReason
import io.putdotio.android.downloads.DownloadNotificationRetries
import io.putdotio.android.downloads.DownloadOutcome
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.MobileDownloadActionReceiver
import io.putdotio.android.downloads.MobileDownloadNotifications
import io.putdotio.android.downloads.MobileDownloadStore
import io.putdotio.android.downloads.SignedInAccount
import io.putdotio.android.downloads.downloadPreferences
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.widgets.MobileTransfersWidgetProvider
import io.putdotio.android.widgets.MobileWidgets
import io.putdotio.android.widgets.TransfersWidgetSource
import io.putdotio.android.widgets.TransfersWidgetUpdater
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * The Transfers widget on the real launcher and the download notifications' actions in the real shade,
 * over a controlled session and synthetic rows: no API call, credential or sign-in. Run it on an
 * emulator you booted: the widget it places stays on that home screen.
 */
@RunWith(AndroidJUnit4::class)
class MobileHomeScreenProofTest {
    @get:Rule val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Home-screen proof requires opt-in", arguments.getString("putio.homescreen.enabled") == "true")
                require(Build.VERSION.SDK_INT == 37) { "Home-screen proof requires API 37" }
                runId()
                // The shade and the launcher are other apps' windows; reading them needs every window.
                val automation = instrumentation.uiAutomation
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                base.evaluate()
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val auth = MutableStateFlow<MobileAuthState>(MobileAuthState.SignedOut())
    private var transfers: List<TransferItem> = emptyList()

    @After
    fun tearDown() {
        MobileWidgets.transfersForTest = null
        MobileDownloadActionReceiver.retriesForTest = null
        context.getSystemService(NotificationManager::class.java).cancelAll()
        downloadPreferences(context).edit(commit = true) {
            remove("user-$USER")
            remove("user-$OTHER")
        }
        scope.cancel()
    }

    @Test
    fun theTransfersWidgetFollowsTheSessionAndForgetsItOnSignOut() {
        MobileWidgets.transfersForTest = TransfersWidgetUpdater(
            context = context,
            source = TransfersWidgetSource(
                authState = auth,
                settledSession = { auth.value },
                load = { PutioResult.Success(transfers) },
                rejectSession = { error("No session is rejected in this lane") },
            ),
            scope = scope,
        )
        val provider = ComponentName(context, MobileTransfersWidgetProvider::class.java)
        val widgets = AppWidgetManager.getInstance(context)
        if (widgets.getAppWidgetIds(provider).isEmpty()) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitCondition("MainActivity resumed") { scenario.state == Lifecycle.State.RESUMED }
                check(widgets.requestPinAppWidget(provider, null, null)) { "The launcher cannot pin widgets" }
                tap(awaitNode("the launcher's add button") { node ->
                    node.text?.toString()?.let(ADD_BUTTON::matches) == true
                })
                awaitCondition("the widget placed") { widgets.getAppWidgetIds(provider).isNotEmpty() }
            }
        }
        home()
        awaitText(SIGNED_OUT)
        screenshot("01-widget-signed-out")

        transfers = FIRST_ACCOUNT
        auth.value = signedIn(USER, 1L)
        MobileWidgets.transfers(context).refresh()
        awaitText(FIRST_ACCOUNT.first().name)
        screenshot("02-widget-signed-in")

        auth.value = MobileAuthState.SignedOut()
        MobileWidgets.transfers(context).clear()
        awaitText(SIGNED_OUT)
        for (transfer in FIRST_ACCOUNT) assertNull(transfer.name, findNode(transfer.name))
        screenshot("03-widget-cleared-on-sign-out")

        transfers = NEXT_ACCOUNT
        auth.value = signedIn(OTHER, 2L)
        MobileWidgets.transfers(context).refresh()
        awaitText(NEXT_ACCOUNT.first().name)
        for (transfer in FIRST_ACCOUNT) assertNull(transfer.name, findNode(transfer.name))
        screenshot("04-widget-next-account")
    }

    @Test
    fun downloadNotificationActionsActOnlyForTheirOwnAccount() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val finished = row(9001L, "Sintel.mkv", DownloadStatus.Completed(1_024L))
        val failed = row(9002L, "Tears of Steel.mkv", DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))
        val otherAccounts = row(9003L, "Another account.mkv", DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L))
        runBlocking {
            MobileDownloadStore(context, USER).apply {
                upsert(finished)
                upsert(failed)
            }
            MobileDownloadStore(context, OTHER).upsert(otherAccounts)
        }
        val started = CopyOnWriteArrayList<Pair<Long, FilesItemId>>()
        MobileDownloadActionReceiver.retriesForTest = DownloadNotificationRetries(
            signedIn = { SignedInAccount.User(USER) },
            find = { userId, fileId -> MobileDownloadStore.peek(context, userId, fileId) },
            // Stands in for Media3: the real start also replaces the outcome notification.
            start = { userId, entry ->
                started += userId to entry.fileId
                MobileDownloadNotifications.cancel(context, userId, entry.fileId)
            },
            dismiss = { userId, fileId -> MobileDownloadNotifications.cancel(context, userId, fileId) },
        )

        MobileDownloadNotifications.post(context, USER, failed, DownloadOutcome.Failed(DownloadFailureReason.NETWORK))
        openShade()
        awaitNotification("Tears of Steel.mkv")
        screenshot("01-failed-offers-try-again")
        tap(awaitAction("Try again"))
        awaitCondition("the owner's retry") { started.contains(USER to failed.fileId) }
        awaitCondition("the outcome replaced") { !posted(USER, failed.fileId) }
        screenshot("02-retried-for-its-account")
        closeShade()

        // A notification an earlier account left behind: Try again under this account retries nothing.
        MobileDownloadNotifications.post(
            context,
            OTHER,
            otherAccounts,
            DownloadOutcome.Failed(DownloadFailureReason.NETWORK),
        )
        openShade()
        awaitNotification("Another account.mkv")
        screenshot("03-another-accounts-outcome")
        tap(awaitAction("Try again"))
        awaitCondition("the other account's outcome removed") { !posted(OTHER, otherAccounts.fileId) }
        assertEquals(listOf(USER to failed.fileId), started.toList())
        screenshot("04-another-account-refused")
        closeShade()

        MobileDownloadNotifications.post(context, USER, finished, DownloadOutcome.Completed)
        openShade()
        awaitNotification("Sintel.mkv")
        screenshot("05-finished-offers-play")
        tap(awaitAction("Play"))
        val activity = awaitResumedMainActivity()
        // Signed out here, so MainActivity holds the scoped link until sign-in; the shell then plays the copy.
        assertEquals(
            MobileDeepLink.Downloads(finished.fileId, play = true, userId = USER),
            activity.deepLinkRequests.pending.value,
        )
        SystemClock.sleep(SETTLE_MS)
        screenshot("06-play-opens-the-app")
        instrumentation.runOnMainSync { activity.finish() }
    }

    /** A notification action; the shade may show the notification collapsed, so it is expanded first. */
    private fun awaitAction(label: String): AccessibilityNodeInfo {
        val action = { node: AccessibilityNodeInfo -> node.text?.toString() == label && node.isClickable }
        nodes().firstOrNull(action)?.let { return it }
        nodes().firstOrNull { it.contentDescription?.toString()?.startsWith("Expand") == true }?.let(::tap)
        return awaitNode(label, action)
    }

    private fun posted(userId: Long, fileId: FilesItemId): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.tag == "download:$userId:${fileId.value}" }

    private fun awaitResumedMainActivity(): MainActivity {
        var resumed: MainActivity? = null
        awaitCondition("MainActivity resumed from the notification") {
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
            }
            resumed != null
        }
        return checkNotNull(resumed)
    }

    private fun openShade() {
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        SystemClock.sleep(SETTLE_MS)
    }

    private fun closeShade() {
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        SystemClock.sleep(SETTLE_MS)
    }

    private fun home() {
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        SystemClock.sleep(SETTLE_MS)
    }

    /** An expanded notification reads its title, the name and the reason as one text. */
    private fun awaitNotification(name: String) =
        awaitNode("the notification for $name") { it.text?.toString()?.lines()?.contains(name) == true }

    private fun awaitText(text: String) = awaitNode("'$text' on screen") { it.text?.toString() == text }

    private fun findNode(text: String): AccessibilityNodeInfo? = nodes().firstOrNull { it.text?.toString() == text }

    private fun awaitNode(label: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        awaitCondition(label) {
            found = nodes().firstOrNull(matches)
            found != null
        }
        return checkNotNull(found)
    }

    /** Every visible node of every window: the shade and the launcher are not this app's windows. */
    private fun nodes(): List<AccessibilityNodeInfo> {
        val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root } +
            listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val all = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) all += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return all
    }

    /** A real touch, as a person taps. */
    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
            .use { FileInputStream(it.fileDescriptor).readBytes() }
        SystemClock.sleep(SETTLE_MS)
    }

    private fun awaitCondition(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) {
                "Timed out waiting for $label; on screen: ${nodes().mapNotNull { it.text?.toString() }}"
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun screenshot(label: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(IDLE_MS, TIMEOUT_MS)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "homescreen-proof-${runId()}")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.homescreen.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private companion object {
        const val USER = 147L
        const val OTHER = 148L
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 200L
        const val IDLE_MS = 300L
        const val SETTLE_MS = 1_500L
        const val SIGNED_OUT = "Sign in to put.io to see your transfers."
        val ADD_BUTTON = Regex("(?i)add( to home screen)?")

        val FIRST_ACCOUNT = listOf(
            transfer(1L, "ubuntu-26.04-desktop-amd64.iso", AppTransferStatus.Downloading, 42.0),
            transfer(2L, "Big Buck Bunny (2008) 1080p", AppTransferStatus.Queued, null),
            transfer(3L, "Sintel.2010.4K.mkv", AppTransferStatus.Seeding, 100.0),
            transfer(4L, "debian-13.1.0-amd64-netinst.iso", AppTransferStatus.Waiting, 0.0),
        )
        val NEXT_ACCOUNT = listOf(
            transfer(9L, "Tears of Steel (2012) 4K", AppTransferStatus.Downloading, 7.0),
        )

        fun signedIn(userId: Long, session: Long) = MobileAuthState.SignedIn(
            account = MobileAccount(userId = userId, username = "proof$userId", email = "proof@example.invalid"),
            sessionId = MobileAuthSessionId(session),
        )

        fun row(fileId: Long, name: String, status: DownloadStatus) = DownloadEntry(
            FilesItemId(fileId), name, PutioFileType.VIDEO, DownloadArtifact.HLS, status,
            createdAt = fileId, accepted = true,
        )

        fun transfer(id: Long, name: String, status: AppTransferStatus, percent: Double?) = TransferItem(
            id = TransferId(id),
            name = name,
            status = status,
            fileId = null,
            sizeBytes = null,
            percentDone = percent,
            downloadSpeedBytesPerSecond = null,
            uploadSpeedBytesPerSecond = null,
            estimatedSecondsRemaining = null,
            availability = null,
            errorMessage = null,
            createdAt = "2026-10-04T00:00:00",
            userFileExists = null,
        )
    }
}
