package io.putdotio.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileSessionKey
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCopyId
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.MOBILE_FILES_LIST_TAG
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.audioAttributes
import io.putdotio.android.playback.playbackRenderersFactory
import io.putdotio.android.search.MOBILE_SEARCH_FIELD_TAG
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import io.putdotio.android.share.MobileFileDragOut
import io.putdotio.android.share.MobileFileShareService
import io.putdotio.android.share.MobileShareDependencies
import io.putdotio.android.transfers.MOBILE_TRANSFER_DROP_TARGET_TAG
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Synthetic tablet proof on a large-screen emulator through the production shell and player over
 * faked repositories and a local video: hardware-keyboard navigation and shortcuts, then drag and
 * drop with another app in split screen (the test package's drag probe, its own process) while the
 * playing video and the shell keep their state through the split. The drag-out export downloads from
 * an in-process source under a controlled session. No API calls, no sign-in.
 */
@RunWith(AndroidJUnit4::class)
class MobileTabletPowerProofTest {
    private val compose = createAndroidComposeRule<MobileTabletProofActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Tablet proof requires opt-in", arguments.getString("putio.tablet.enabled") == "true")
                require(Build.VERSION.SDK_INT == 37) { "Tablet proof requires API 37" }
                require(context.resources.configuration.smallestScreenWidthDp >= 600) {
                    "Tablet proof requires a large screen; see docs/harness.md"
                }
                runId()
                base.evaluate()
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    private val poster = posterJpeg()
    private val files = ProofFiles(posterSize = poster.size.toLong())
    private val players = ProofPlayers()
    private val draft = MobileTransferDraft()

    @Test
    fun keyboardDrivesFilesSearchTrashAndThePlayer() = withShell {
        screenshot("keys-01-files")

        // The first key leaves touch mode; Tab walks in until a row holds focus.
        repeat(MAX_TABS) { if (!rowFocused("Shows")) press(KeyEvent.KEYCODE_TAB) }
        row("Shows").assertIsFocused()
        screenshot("keys-02-row-focused")

        press(KeyEvent.KEYCODE_ENTER)
        awaitCondition("Shows opened") { rowFocused("Episode 1.mp4") }
        screenshot("keys-03-folder-opened-first-row-focused")

        press(KeyEvent.KEYCODE_DEL)
        awaitCondition("back at root") { rowFocused("Shows") }
        screenshot("keys-04-backspace-went-up")

        press(KeyEvent.KEYCODE_F, CTRL)
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsFocused()
        screenshot("keys-05-ctrl-f-search")
        press(KeyEvent.KEYCODE_ESCAPE)
        awaitCondition("Files again") { rowExists("Shows") }

        val loads = files.loads.get()
        press(KeyEvent.KEYCODE_F5)
        awaitCondition("F5 refreshed") { files.loads.get() > loads }

        focusRow("notes.txt")
        screenshot("keys-06-notes-focused")
        press(KeyEvent.KEYCODE_FORWARD_DEL)
        compose.waitUntil(TIMEOUT_MS) { files.deleted == listOf(NOTES.id to FilesDeleteMode.TRASH) }
        compose.onNodeWithText("Moved to Trash").assertExists()
        // The focused row left the listing; keyboard focus stays in it, on the row now in its place.
        awaitCondition("a row keeps focus") { rowFocused("poster.jpg") }
        screenshot("keys-07-delete-moved-to-trash")

        compose.activity.requestShowKeyboardShortcuts()
        SystemClock.sleep(SETTLE_MS)
        screenshot("keys-08-shortcuts-helper")
        compose.activity.dismissKeyboardShortcutsHelper()
        SystemClock.sleep(SETTLE_MS)

        focusRow("Harbor film.mp4")
        press(KeyEvent.KEYCODE_ENTER)
        awaitCondition("video playing") { player { it.isPlaying } == true }
        dismissFullScreenHint()
        screenshot("keys-09-enter-played-the-video")
        press(KeyEvent.KEYCODE_SPACE)
        awaitCondition("space paused") { player { it.playWhenReady } == false }
        val paused = requireNotNull(player { it.currentPosition })
        press(KeyEvent.KEYCODE_DPAD_RIGHT, pauseMs = 0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, pauseMs = 0)
        awaitCondition("arrows seeked") { requireNotNull(player { it.currentPosition }) >= paused + 19_000L }
        screenshot("keys-10-space-paused-arrows-seeked")
        press(KeyEvent.KEYCODE_SPACE)
        awaitCondition("space played") { player { it.playWhenReady } == true }
        press(KeyEvent.KEYCODE_ESCAPE)
        awaitCondition("left the player") { rowExists("Harbor film.mp4") }
        assertEquals(1, players.created.get())
        screenshot("keys-11-escape-left-the-player")
    }

    @Test
    fun dragAndDropWithAnotherAppInSplitScreenKeepsTheVideoAndShell() = withShell {
        val host = compose.activity
        focusRow("Harbor film.mp4")
        press(KeyEvent.KEYCODE_ENTER)
        awaitCondition("video playing") { player { it.isPlaying } == true }
        dismissFullScreenHint()
        screenshot("drag-01-video-full-screen")

        val reports = ProbeReports()
        try {
            host.startActivity(
                Intent().setClassName(probePackage, PROBE_ACTIVITY)
                    .putExtra("replyPackage", context.packageName)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                    ),
            )
            awaitCondition("probe beside put.io") { probeWindow() != null && host.isInMultiWindowMode }
            SystemClock.sleep(SETTLE_MS)
            // The split resized the window without recreating it: the same Activity and player play on.
            assertSame(host, compose.activity)
            assertEquals(1, players.created.get())
            awaitCondition("video still playing") { player { it.isPlaying } == true }
            screenshot("drag-02-video-keeps-playing-in-split-screen")

            // The app opened beside put.io holds key focus; a tap on put.io's pane gives it back.
            tap(hostCenter())
            press(KeyEvent.KEYCODE_ESCAPE)
            awaitCondition("Files in the split") { rowExists("poster.jpg") }
            screenshot("drag-03-files-in-split-screen")

            touchDrag(from = rowCenter("poster.jpg"), to = probeCenter("Drop a put.io file here"))
            val report = reports.next()
            assertEquals("poster.jpg", report.getStringExtra("name"))
            assertEquals("image/jpeg", report.getStringExtra("type"))
            assertEquals(poster.size, report.getIntExtra("size", -1))
            assertEquals(sha256(poster), report.getStringExtra("sha256"))
            val uri = Uri.parse(report.getStringExtra("uri"))
            assertEquals("${context.packageName}.drag", uri.authority)
            assertFalse(report.getStringExtra("uri").orEmpty().contains(PROOF_TOKEN))
            assertTrue("the drop's grant", report.getBooleanExtra("granted", false))
            assertFalse("readable before the grant", report.getBooleanExtra("readBeforeRequest", true))
            assertFalse("writable", report.getBooleanExtra("writeWithGrant", true))
            assertFalse("readable after release", report.getBooleanExtra("readAfterRelease", true))
            SystemClock.sleep(SETTLE_MS)
            screenshot("drag-04-file-dropped-into-the-other-app")

            touchDrag(from = probeCenter("Harbor film.torrent"), to = hostCenter()) {
                compose.waitUntil(TIMEOUT_MS) { tagExists(MOBILE_TRANSFER_DROP_TARGET_TAG) }
                screenshot("drag-05-drop-target-shown")
            }
            awaitCondition("torrent draft") { draft.state.value.torrent != null }
            assertEquals("Harbor film.torrent", draft.state.value.torrent?.fileName)
            SystemClock.sleep(SETTLE_MS)
            screenshot("drag-06-torrent-opened-add-transfer")

            // An open sheet is its own window and takes no drops, so the next drag starts from Transfers.
            compose.onNodeWithText("Cancel").performClick()
            // A drag registers its windows when it starts; wait until the sheet's window is gone.
            awaitCondition("the sheet's window to close") { appWindowCount() == 1 }
            SystemClock.sleep(SETTLE_MS)
            touchDrag(from = probeCenter("Magnet link"), to = hostCenter())
            // Cancel kept the torrent as a draft, so the drop asks before replacing it, as a share does.
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Use shared link")).fetchSemanticsNodes().isNotEmpty() }
            screenshot("drag-07-replace-the-torrent-draft")
            compose.onNodeWithText("Use shared link").performClick()
            awaitCondition("magnet draft") { draft.state.value.input.startsWith("magnet:") }
            SystemClock.sleep(SETTLE_MS)
            screenshot("drag-08-magnet-opened-add-transfer")
        } finally {
            context.sendBroadcast(Intent(PROBE_FINISH).setPackage(probePackage))
            reports.close()
        }
    }

    private fun withShell(block: () -> Unit) {
        val auth = MutableStateFlow<MobileAuthState>(SignedIn)
        MobileFileShareService.dependenciesForTest = MobileShareDependencies(
            http = OkHttpClient.Builder().addInterceptor(PosterSource(poster)).build(),
            authState = auth,
            accessToken = { PROOF_TOKEN },
        )
        val video = File(requireNotNull(arguments.getString("putio.tablet.video"))).canonicalFile
        val externalFiles = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        require(video.isFile && video.toPath().startsWith(externalFiles.toPath())) { "Fixture must be under $externalFiles" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = FilesBrowserController(files, scope)
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            compose.setContent {
                val filesState by controller.state.collectAsState()
                PutioTheme {
                    MobileShell(
                        transferDraft = draft,
                        filesState = filesState,
                        filesRepository = files,
                        accountSettingsState = readyAccountSettingsState(),
                        appConfigState = readyAndroidAppConfigState(),
                        account = Account,
                        playbackRepository = LocalVideo(video),
                        playbackPlayerFactory = players,
                        sessionId = Session,
                        onFilesEvent = controller::dispatch,
                        onAccountSettingsEvent = {},
                        onPlaybackAuthenticationRequired = {},
                        onShareItem = {},
                        fileDragOut = MobileFileDragOut(context, MobileSessionKey(Account.userId, Session)),
                        onSignOut = {},
                    )
                }
            }
            compose.waitUntil(TIMEOUT_MS) { controller.state.value.current.content is FilesContent.Ready }
            block()
        } finally {
            controller.close()
            scope.cancel()
            MobileFileShareService.dependenciesForTest = null
        }
    }

    /** Reads the screen's player on its own thread. */
    private fun <T> player(read: (Player) -> T): T? {
        var value: T? = null
        instrumentation.runOnMainSync { value = players.current?.let(read) }
        return value
    }

    private fun row(name: String) =
        compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag(MOBILE_FILES_LIST_TAG)))

    private fun rowExists(name: String): Boolean =
        compose.onAllNodes(hasText(name) and hasAnyAncestor(hasTestTag(MOBILE_FILES_LIST_TAG)))
            .fetchSemanticsNodes().isNotEmpty()

    private fun rowFocused(name: String): Boolean = rowExists(name) && runCatching { row(name).assertIsFocused() }.isSuccess

    private fun tagExists(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** Arrows walk the listing to [name]; the order is Shows, Harbor film.mp4, poster.jpg, notes.txt. */
    private fun focusRow(name: String) {
        repeat(MAX_TABS) { if (ROOT_ORDER.none(::rowFocused)) press(KeyEvent.KEYCODE_TAB) }
        repeat(ROOT_ORDER.size * 2) {
            if (rowFocused(name)) return
            val at = ROOT_ORDER.indexOfFirst(::rowFocused)
            press(if (at < ROOT_ORDER.indexOf(name)) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        }
        row(name).assertIsFocused()
    }

    private fun press(keyCode: Int, meta: Int = 0, pauseMs: Long = KEY_PAUSE_MS) {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, meta))
        compose.waitForIdle()
        SystemClock.sleep(pauseMs)
    }

    /** A finger held past the long-press timeout, then moved across windows and lifted. */
    private fun touchDrag(from: PointF, to: PointF, beforeRelease: () -> Unit = {}) {
        val downTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, downTime, from)
        SystemClock.sleep(LONG_PRESS_HOLD_MS)
        for (step in 1..DRAG_STEPS) {
            val fraction = step / DRAG_STEPS.toFloat()
            inject(MotionEvent.ACTION_MOVE, downTime, PointF(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction))
            SystemClock.sleep(DRAG_STEP_MS)
        }
        SystemClock.sleep(SETTLE_MS)
        beforeRelease()
        inject(MotionEvent.ACTION_UP, downTime, to)
        SystemClock.sleep(SETTLE_MS)
    }

    private fun tap(point: PointF) {
        val downTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, downTime, point)
        inject(MotionEvent.ACTION_UP, downTime, point)
        SystemClock.sleep(SETTLE_MS)
    }

    private fun inject(action: Int, downTime: Long, point: PointF) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            check(uiAutomation.injectInputEvent(event, true)) { "Injection refused at $point" }
        } finally {
            event.recycle()
        }
    }

    private fun rowCenter(name: String): PointF = screenPoint(row(name).fetchSemanticsNode().boundsInWindow.center)

    private fun hostCenter(): PointF {
        val origin = IntArray(2).also(compose.activity.window.decorView::getLocationOnScreen)
        val view = compose.activity.window.decorView
        return PointF(origin[0] + view.width / 2f, origin[1] + view.height / 2f)
    }

    private fun screenPoint(offset: androidx.compose.ui.geometry.Offset): PointF {
        val origin = IntArray(2).also(compose.activity.window.decorView::getLocationOnScreen)
        return PointF(origin[0] + offset.x, origin[1] + offset.y)
    }

    /** Android's first-use full-screen hint takes key focus from the player; the proof answers it once. */
    private fun dismissFullScreenHint() {
        SystemClock.sleep(SETTLE_MS)
        uiAutomation.windows.filter { it.root?.packageName != context.packageName }
            .firstNotNullOfOrNull { it.root?.findAccessibilityNodeInfosByText("Got it")?.firstOrNull() }
            ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        SystemClock.sleep(SETTLE_MS)
    }

    private fun appWindowCount(): Int {
        val dump = uiAutomation.executeShellCommand("dumpsys window windows")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(dump).bufferedReader().use { it.readText() }
        return Regex("""Window #\d+ Window\{\w+ u0 ${Regex.escape(context.packageName)}/""").findAll(text).count()
    }

    private fun probeWindow(): AccessibilityWindowInfo? =
        uiAutomation.windows.firstOrNull { it.root?.packageName == probePackage }

    private fun probeCenter(text: String): PointF {
        val node = requireNotNull(probeWindow()?.root?.findAccessibilityNodeInfosByText(text)?.firstOrNull()) {
            "No \"$text\" in the probe"
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        return PointF(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun awaitCondition(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $label" }
            compose.waitForIdle()
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun screenshot(label: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        uiAutomation.waitForIdle(100, 3_000)
        // Holds each state long enough for a screen recording to show it.
        SystemClock.sleep(STEP_PAUSE_MS)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tablet-proof-${runId()}")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    /** The probe's reports, which it broadcasts to this app after each drop. */
    private inner class ProbeReports : Closeable {
        private val queue = LinkedBlockingQueue<Intent>()
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                queue += intent
            }
        }

        init {
            context.registerReceiver(receiver, IntentFilter(PROBE_RECEIVED), Context.RECEIVER_EXPORTED)
        }

        fun next(): Intent = requireNotNull(queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "The probe reported no drop" }

        override fun close() = context.unregisterReceiver(receiver)
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tablet.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation: UiAutomation get() = instrumentation.uiAutomation
    private val context: Context get() = instrumentation.targetContext
    private val probePackage: String get() = instrumentation.context.packageName

    private companion object {
        const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val MAX_TABS = 16
        const val KEY_PAUSE_MS = 700L
        const val STEP_PAUSE_MS = 1_200L
        const val SETTLE_MS = 1_500L
        const val LONG_PRESS_HOLD_MS = 1_200L
        const val DRAG_STEPS = 40
        const val DRAG_STEP_MS = 20L
        const val POLL_MS = 100L
        const val TIMEOUT_MS = 20_000L
        const val PROOF_TOKEN = "tablet-proof-token"
        const val PARTIAL_CONTENT = 206
        const val PROBE_ACTIVITY = "io.putdotio.android.probe.DragProbeActivity"
        const val PROBE_RECEIVED = "io.putdotio.android.probe.RECEIVED"
        const val PROBE_FINISH = "io.putdotio.android.probe.FINISH"
        val Session = MobileAuthSessionId(1L)
        val Account = MobileAccount(userId = 1L, username = "tablet", email = "tablet@example.com")
        val SignedIn = MobileAuthState.SignedIn(Account, Session)
        val SHOWS = item(1, "Shows", PutioFileType.FOLDER)
        val FILM = item(2, "Harbor film.mp4", PutioFileType.VIDEO)
        val POSTER = item(3, "poster.jpg", PutioFileType.IMAGE)
        val NOTES = item(4, "notes.txt", PutioFileType.TEXT)
        val EPISODE = item(5, "Episode 1.mp4", PutioFileType.VIDEO).copy(parentId = SHOWS.id)
        val ROOT_ORDER = listOf(SHOWS, FILM, POSTER, NOTES).map(FilesItem::name)

        fun item(id: Long, name: String, type: PutioFileType) =
            FilesItem(FilesItemId(id), FilesFolder.Root.id, name, type, 48_000_000L, "2026-10-04T12:00:00Z")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun posterJpeg(): ByteArray {
            val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.rgb(0xFD, 0xCE, 0x45))
                drawText("poster.jpg from put.io", 60f, 290f, Paint().apply { textSize = 64f; color = Color.BLACK })
            }
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                bitmap.recycle()
                output.toByteArray()
            }
        }
    }

    /** Root and one folder; Trash removes an item, and an exact-ID read then reports it gone. */
    private class ProofFiles(posterSize: Long) : FilesRepository {
        val loads = AtomicInteger()
        private val root = mutableListOf(SHOWS, FILM, POSTER.copy(sizeBytes = posterSize), NOTES)

        @Volatile var deleted: List<Pair<FilesItemId, FilesDeleteMode>> = emptyList()
            private set

        override suspend fun loadFolder(folderId: FilesItemId): PutioResult<FilesPage> {
            loads.incrementAndGet()
            return PutioResult.Success(FilesPage(if (folderId == SHOWS.id) listOf(EPISODE) else root.toList(), null))
        }

        override suspend fun delete(itemId: FilesItemId, mode: FilesDeleteMode): PutioResult<FileDeleteResult> {
            deleted = deleted + (itemId to mode)
            root.removeAll { it.id == itemId }
            return PutioResult.Success(FileDeleteResult(status = "OK"))
        }

        override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> =
            (root + EPISODE).firstOrNull { it.id == itemId }?.let { PutioResult.Success(it) }
                ?: PutioResult.Failure(notFound(itemId))

        override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<FilesPage> = error("One page")
        override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) = error("No move")
        override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort) = error("No sort")
        override suspend fun rename(itemId: FilesItemId, name: String) = error("No rename")
        override suspend fun move(itemId: FilesItemId, destinationId: FilesItemId): PutioResult<List<FileMoveError>> =
            error("No move")
        override suspend fun startCopy(itemId: FilesItemId, destinationId: FilesItemId) = error("No copy")
        override suspend fun checkCopy(copyId: FilesCopyId) = error("No copy")

        private fun notFound(itemId: FilesItemId) = PutioApiException(
            request = PutioRequestData("GET", "https://api.put.io/v2/files/${itemId.value}"),
            resolvedStatusCode = NOT_FOUND,
            httpStatusCode = NOT_FOUND,
            resolvedErrorType = "NotFound",
            envelope = PutioApiErrorEnvelope(statusCode = NOT_FOUND, errorType = "NotFound"),
            responseBody = "{}",
            message = "Synthetic missing item",
        ).toPutioFailure()

        private companion object {
            const val NOT_FOUND = 404
        }
    }

    /** Resolves every video to the caller's local fixture. */
    private class LocalVideo(file: File) : PlaybackRepository {
        // The SDK owns production URLs. This proof reads only its caller-owned local fixture.
        private val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(Uri.fromFile(file).toString())

        override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(
                PlaybackResolution.Ready(
                    PlaybackSource(
                        fileId = target.fileId.value,
                        kind = PlaybackSourceKind.ORIGINAL,
                        url = url,
                        startFromSeconds = 0.0,
                        subtitles = PlaybackSubtitles.None,
                    ),
                ),
            )

        override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
    }

    /**
     * The production renderers on a player that reads local files; the production player streams
     * through the download cache's HTTP source. It never attaches to a real audio session.
     */
    private class ProofPlayers : MobilePlayerFactory {
        val created = AtomicInteger()

        @Volatile var current: Player? = null
            private set

        override fun create(context: Context, mediaType: PlaybackMediaType): Player =
            ExoPlayer.Builder(context, playbackRenderersFactory(context))
                .setAudioAttributes(mediaType.audioAttributes(), false)
                .build()
                .also {
                    created.incrementAndGet()
                    current = it
                }

        override fun connectAudio(context: Context, onResult: (Result<Player>) -> Unit): Closeable {
            onResult(Result.failure(IllegalStateException("No audio in the tablet proof")))
            return Closeable {}
        }

        override fun stopAudio(context: Context) = Unit
    }

    /** Serves poster.jpg for the drag-out reads, whole or from a range; the session travels only in the header. */
    private class PosterSource(private val body: ByteArray) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            check(request.header("Authorization") == "Token $PROOF_TOKEN")
            check(!request.url.toString().contains(PROOF_TOKEN))
            val start = request.header("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(PARTIAL_CONTENT)
                .message("Partial Content")
                .header("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                .body(body.copyOfRange(start, body.size).toResponseBody("image/jpeg".toMediaType()))
                .build()
        }
    }
}
