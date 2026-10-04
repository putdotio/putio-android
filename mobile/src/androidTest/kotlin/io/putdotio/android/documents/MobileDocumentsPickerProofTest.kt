package io.putdotio.android.documents

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.PutioResult
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.search.SearchTerm
import io.putdotio.sdk.files.PutioFileType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Synthetic proof of the documents provider through the system file picker: another activity sends
 * ACTION_OPEN_DOCUMENT, the real DocumentsUI lists the put.io root and pages a folder in, and the picked documents
 * are read through the production provider's proxy descriptors. A faked session and Files repository stand in for
 * put.io; the remote file comes through the share-out download request served in-process, and the local one from a
 * stand-in for a completed download. No API call, credential or sign-in UI.
 */
@RunWith(AndroidJUnit4::class)
class MobileDocumentsPickerProofTest {
    @get:Rule val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Documents picker proof requires opt-in",
                    arguments.getString("putio.documents.enabled") == "true")
                runId()
                base.evaluate()
            }
        }
    }

    @Test
    fun anotherAppPicksPutioFilesUntilSignOut() {
        val auth = MutableStateFlow<MobileAuthState>(SIGNED_IN)
        val files = ProofFiles()
        val source = PosterSource(POSTER_BYTES)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        MobileDocumentsProvider.documentsForTest = MobileDocumentsProvider.documents(
            context,
            MobileDocumentsBackend(
                authState = auth,
                files = files,
                search = NoSearch,
                offline = { _, fileId ->
                    LOCAL_COPY.takeIf { fileId == AUDIO.id }?.let { bytes ->
                        DocumentBytes(bytes.size.toLong(), onDevice = true) { offset ->
                            OpenedBytes(bytes.inputStream(offset.toInt(), bytes.size - offset.toInt()))
                        }
                    }
                },
                remote = RemoteOriginals(OkHttpClient.Builder().addInterceptor(source).build()) { TOKEN }::bytes,
                onAuthenticationRequired = {},
            ),
            scope,
        )
        MobileDocumentsProvider.sessionStarted(context)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                val picker = Picker(scenario)
                picker.show("Pick a file from another app's picker", null)

                picker.open()
                showRoots()
                awaitText(ROOT_SUMMARY)
                screenshot("01-picker-lists-putio")
                // The root's title also names other nodes; its summary, the account name, is unique.
                tap(ROOT_SUMMARY)
                awaitText(FOLDER.name)
                screenshot("02-putio-root")
                tap(FOLDER.name)
                awaitCondition("first page") { files.pagesServed >= 1 }
                SystemClock.sleep(PAGE_DELAY_MS / 2)
                screenshot("03-folder-first-page-loading")
                awaitCondition("every page") { files.pagesServed == 3 }
                awaitText("page-item-001.txt")
                SystemClock.sleep(SETTLE_MS)
                screenshot("04-folder-every-page")
                back()
                awaitText(POSTER.name)
                tap(POSTER.name)

                val posterUri = checkNotNull(picker.await()) { "The picker returned nothing" }
                val posterBytes = read(posterUri)
                assertArrayEquals(source.body, posterBytes)
                assertTrue(source.requests.isNotEmpty())
                source.requests.forEach { request ->
                    assertEquals("https://api.put.io/v2/files/${POSTER.id.value}/download", request.url.toString())
                    assertEquals("Token $TOKEN", request.header("Authorization"))
                }
                picker.show(
                    "${POSTER.name}: ${posterBytes.size} bytes streamed from put.io in " +
                        "${source.requests.size} ranged request(s)",
                    BitmapFactory.decodeByteArray(posterBytes, 0, posterBytes.size),
                )
                screenshot("05-picked-remote-poster")

                val requestsBefore = source.requests.size
                picker.open()
                if (!hasText(AUDIO.name)) {
                    showRoots()
                    tap(ROOT_SUMMARY)
                }
                awaitText(AUDIO.name)
                tap(AUDIO.name)
                val audioUri = checkNotNull(picker.await()) { "The picker returned nothing" }
                assertArrayEquals(LOCAL_COPY, read(audioUri))
                assertEquals("The local copy must not reach the network", requestsBefore, source.requests.size)
                picker.show("${AUDIO.name}: ${LOCAL_COPY.size} bytes read from this device, no request", null)
                screenshot("06-picked-local-copy")

                // What the auth runtime runs when a session ends.
                auth.value = MobileAuthState.SignedOut()
                MobileDocumentsProvider.sessionLeft(context)
                awaitCondition("root removed") { rootCount() == 0 }
                assertThrows(FileNotFoundException::class.java) { read(posterUri) }
                picker.show("Signed out: the picked documents no longer open", null)
                screenshot("07-signed-out-documents-refused")
                picker.open()
                showRoots()
                SystemClock.sleep(SETTLE_MS)
                assertTrue("The put.io root must be gone", !hasText(ROOT_SUMMARY))
                screenshot("08-signed-out-no-root")
                // Back closes the roots drawer first, then cancels the picker.
                repeat(2) { if (activeWindowPackage() != context.packageName) back() }
                assertNull(picker.await())
            }
        } finally {
            MobileDocumentsProvider.documentsForTest = null
            scope.cancel()
            MobileDocumentsProvider.sessionStarted(context)
        }
    }

    private fun read(uri: Uri): ByteArray =
        checkNotNull(context.contentResolver.openFileDescriptor(uri, "r")).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }

    private fun rootCount(): Int =
        context.contentResolver.query(
            DocumentsContract.buildRootsUri(MobileDocumentsProvider.authority(context)),
            null,
            Bundle(),
            null,
        )?.use { it.count } ?: 0

    /** The test activity: shows what it picked and asks the system picker through the activity result API. */
    private inner class Picker(private val scenario: ActivityScenario<ComponentActivity>) {
        private val results = LinkedBlockingQueue<Result<Uri?>>()
        private lateinit var status: TextView
        private lateinit var image: ImageView
        private var launch: (() -> Unit)? = null

        init {
            scenario.onActivity { activity ->
                status = TextView(activity).apply {
                    textSize = 20f
                    setPadding(48, 160, 48, 48)
                }
                image = ImageView(activity).apply { adjustViewBounds = true }
                activity.setContentView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        fitsSystemWindows = true
                        addView(status)
                        addView(image)
                    },
                )
                val pick = activity.activityResultRegistry.register(
                    "putio-documents-proof",
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> results.put(Result.success(uri)) }
                launch = { pick.launch(arrayOf("*/*")) }
            }
        }

        /** Shows a step's outcome long enough for a screen recording to hold it. */
        fun show(text: String, bitmap: Bitmap?) {
            scenario.onActivity {
                status.text = text
                image.setImageBitmap(bitmap)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(DWELL_MS)
        }

        fun open() {
            scenario.onActivity { checkNotNull(launch).invoke() }
            awaitCondition("system picker") { activeWindowPackage() !in listOf(null, context.packageName) }
            SystemClock.sleep(SETTLE_MS)
        }

        fun await(): Uri? =
            checkNotNull(results.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "No picker result" }.getOrThrow()
    }

    private fun showRoots() {
        findNode("Show roots")?.let { tapNode(it) }
        SystemClock.sleep(SETTLE_MS)
    }

    private fun back() {
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        SystemClock.sleep(SETTLE_MS)
    }

    private fun hasText(text: String): Boolean = findNode(text) != null

    private fun awaitText(text: String) = awaitCondition("'$text' on screen") { hasText(text) }

    private fun tap(text: String) {
        awaitText(text)
        instrumentation.uiAutomation.waitForIdle(IDLE_MS, TIMEOUT_MS)
        tapNode(checkNotNull(findNode(text)))
        SystemClock.sleep(SETTLE_MS)
    }

    /** Exact text or content description in the active window. */
    private fun findNode(text: String): AccessibilityNodeInfo? =
        instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText(text)
            ?.firstOrNull { (it.text?.toString() ?: it.contentDescription?.toString()) == text && it.isVisibleToUser }

    /** A real touch: the picker closes its drawer and opens items on touch, not on an accessibility click. */
    private fun tapNode(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
            .use { FileInputStream(it.fileDescriptor).readBytes() }
    }

    private fun activeWindowPackage(): String? =
        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()

    private fun awaitCondition(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $label" }
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun screenshot(label: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "documents-proof-${runId()}")
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

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.documents.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** A root with a folder of three pages, a poster put.io streams and an audio file with a local copy. */
    private class ProofFiles : StubFilesRepository() {
        @Volatile var pagesServed = 0

        override suspend fun loadFolder(folderId: FilesItemId): PutioResult<FilesPage> =
            when (folderId) {
                FOLDER.id -> page(1)
                else -> PutioResult.Success(FilesPage(listOf(FOLDER, POSTER, AUDIO), null))
            }

        override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<FilesPage> =
            page(cursor.value.removePrefix("page-").toInt())

        override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> =
            PutioResult.Success(listOf(FOLDER, POSTER, AUDIO).first { it.id == itemId })

        private suspend fun page(number: Int): PutioResult<FilesPage> {
            // Slow enough that the picker shows each page arriving.
            delay(PAGE_DELAY_MS)
            val first = (number - 1) * PAGE_SIZE + 1
            val items = (first until first + PAGE_SIZE).filter { it <= FOLDER_ITEMS }.map { index ->
                item(1_000L + index, "page-item-%03d.txt".format(index), PutioFileType.TEXT, size = 1_024L)
            }
            pagesServed = number
            return PutioResult.Success(FilesPage(items, FilesCursor("page-${number + 1}").takeIf { number < 3 }))
        }
    }

    private object NoSearch : SearchRepository {
        override suspend fun search(term: SearchTerm): PutioResult<SearchPage> =
            PutioResult.Success(SearchPage(emptyList(), null, total = 0))

        override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage> = error("No paging")
    }

    /** Serves the poster for the share-out download request, honouring its byte range. */
    private class PosterSource(val body: ByteArray) : Interceptor {
        val requests = CopyOnWriteArrayList<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val offset = request.header("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Content-Range", "bytes $offset-${body.size - 1}/${body.size}")
                .body(body.copyOfRange(offset, body.size).toResponseBody())
                .build()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
        const val IDLE_MS = 500L
        const val DWELL_MS = 2_500L
        const val SETTLE_MS = 1_500L
        const val PAGE_DELAY_MS = 1_200L
        const val PAGE_SIZE = 50
        const val FOLDER_ITEMS = 120
        const val TOKEN = "proof-token"
        const val ROOT_SUMMARY = "documents-proof"
        const val USER_ID = 900_101L

        val SIGNED_IN = MobileAuthState.SignedIn(
            account = MobileAccount(userId = USER_ID, username = ROOT_SUMMARY, email = "proof@example.com"),
            sessionId = MobileAuthSessionId(1L),
        )

        fun item(id: Long, name: String, type: PutioFileType, size: Long) = FilesItem(
            id = FilesItemId(id),
            parentId = FilesItemId(0L),
            name = name,
            type = type,
            sizeBytes = size,
            createdAt = "2026-10-01T12:00:00",
            updatedAt = "2026-10-04T09:30:00",
        )

        val FOLDER = item(2L, "Proof folder", PutioFileType.FOLDER, size = 122_880L)
        val POSTER_BYTES = poster()
        val POSTER = item(10L, "harbor-poster.jpg", PutioFileType.IMAGE, size = POSTER_BYTES.size.toLong())
        val AUDIO = item(11L, "harbor.mp3", PutioFileType.AUDIO, size = 262_144L)
        val LOCAL_COPY = ByteArray(262_144) { (it % 251).toByte() }
    }
}

private fun poster(): ByteArray {
    val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        drawColor(Color.rgb(0xFD, 0xCE, 0x45))
        drawText("put.io documents proof", 120f, 380f, Paint().apply {
            textSize = 96f
            color = Color.BLACK
        })
    }
    return ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
        bitmap.recycle()
        output.toByteArray()
    }
}
