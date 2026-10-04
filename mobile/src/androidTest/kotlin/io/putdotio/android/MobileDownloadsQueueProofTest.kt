package io.putdotio.android

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DefaultDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.downloads.DownloadArtifact
import io.putdotio.android.downloads.DownloadEngine
import io.putdotio.android.downloads.DownloadEntry
import io.putdotio.android.downloads.DownloadFailureReason
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_REMOVE_CONFIRM_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_SELECTION_DELETE_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_SELECT_TAG
import io.putdotio.android.downloads.MobileDownloadEngine
import io.putdotio.android.downloads.MobileDownloadNotifications
import io.putdotio.android.downloads.MobileDownloadSettings
import io.putdotio.android.downloads.MobileDownloadStore
import io.putdotio.android.downloads.MobileDownloadsScreen
import io.putdotio.android.downloads.downloadContentId
import io.putdotio.android.downloads.downloadPreferences
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * The production Downloads screen, controller, engine and notifications over a real Media3
 * manager reading generated local files, so no API call, account or session is involved. Five
 * files queue at a limit of 2; the third fails for storage until it is retried.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class MobileDownloadsQueueProofTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Downloads queue proof requires opt-in",
                    arguments.getString("putio.downloads.queue.enabled") == "true")
                UUID.fromString(requireNotNull(arguments.getString("putio.downloads.queue.runId")))
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    private val workDirectory = File(context.cacheDir, "downloads-queue-proof")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = MobileDownloadSettings(context)
    private val previousConcurrency = settings.concurrency
    @Volatile private var failing = true
    private var cleanup: () -> Unit = {}
    private lateinit var controller: DownloadsController

    @After
    fun tearDown() {
        cleanup()
        scope.cancel()
        settings.concurrency = previousConcurrency
        context.deleteDatabase(DATABASE)
        downloadPreferences(context).edit(commit = true) { remove("user-$USER_ID") }
        workDirectory.deleteRecursively()
    }

    @Test
    fun queueHoldsTheLimitAFailureRetriesAndBulkDeleteClearsLocalCopies() {
        assertFalse(
            "Revoke POST_NOTIFICATIONS before this lane so the denied state is proven first",
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
        check(workDirectory.mkdirs() || workDirectory.isDirectory)
        val sources = FILES.associate { (id, _) ->
            id to File(workDirectory, "$id.mp4").apply { writeBytes(Random(id).nextBytes(SOURCE_BYTES)) }
        }
        val store = MobileDownloadStore(context, USER_ID)
        runBlocking {
            for ((index, file) in FILES.withIndex()) {
                store.upsert(DownloadEntry(FilesItemId(file.first), file.second, PutioFileType.VIDEO,
                    DownloadArtifact.ORIGINAL, DownloadStatus.Queued, createdAt = index + 1L, accepted = true))
            }
        }
        lateinit var manager: DownloadManager
        instrumentation.runOnMainSync {
            val database = DefaultDatabaseProvider(ProofDatabase())
            val cache = SimpleCache(File(workDirectory, "cache"), NoOpCacheEvictor(), database)
            val executor = Executors.newFixedThreadPool(2)
            val index = DefaultDownloadIndex(database).apply {
                for ((position, file) in FILES.withIndex()) {
                    putDownload(Download(request(file.first, sources.getValue(file.first)), Download.STATE_QUEUED,
                        position + 1L, position + 1L, C.LENGTH_UNSET.toLong(), Download.STOP_REASON_NONE,
                        Download.FAILURE_REASON_NONE))
                }
            }
            manager = DownloadManager(
                context,
                index,
                DefaultDownloaderFactory(
                    CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { ProofSource() },
                    executor,
                ),
            ).apply {
                requirements = Requirements(0)
                minRetryCount = 0
                addListener(MobileDownloadNotifications(context))
            }
            val engine = MobileDownloadEngine(context, store, USER_ID, scope, manager)
            // Adds and removes go to this manager; the app's own service drives the real one.
            val local = object : DownloadEngine by engine {
                override fun start(entry: DownloadEntry) =
                    manager.addDownload(request(entry.fileId.value, sources.getValue(entry.fileId.value)))

                override fun remove(fileId: FilesItemId) =
                    manager.removeDownload(downloadContentId(USER_ID, fileId))
            }
            local.setConcurrency(2)
            controller = DownloadsController(store, local, scope)
            cleanup = {
                instrumentation.runOnMainSync {
                    controller.close()
                    engine.close()
                    manager.release()
                    cache.release()
                }
                executor.shutdownNow()
            }
        }
        compose.setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            PutioTheme {
                Surface(Modifier.fillMaxSize()) {
                    MobileDownloadsScreen(state, controller::dispatch, onPlay = {}, Modifier.safeDrawingPadding())
                }
            }
        }

        // Two transfers at the limit, three waiting in the order they were added.
        awaitState { it.running() == 2 && it.queue.size == FILES.size }
        val waiting = controller.state.value.queue.filter { it.status == DownloadStatus.Queued }
        assertEquals(FILES.drop(2).map { it.first }, waiting.map { it.fileId.value })
        assertEquals(listOf(1, 2, 3), waiting.map { controller.state.value.queuePosition(it.fileId) })
        screenshot("queue-at-limit-2")

        awaitState { it.entry(FailingId)?.status is DownloadStatus.Failed }
        val failed = controller.state.value.entry(FailingId)?.status as DownloadStatus.Failed
        assertEquals(DownloadFailureReason.STORAGE, failed.reason)
        assertTrue("No notification while denied", activeTags().isEmpty())
        screenshot("failed-while-notifications-denied")

        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        failing = false
        compose.onNodeWithContentDescription("Download actions for ${FILES[2].second}").performClick()
        compose.onNodeWithText("Try again").performClick()
        awaitState { it.entry(FailingId)?.isCompleted == true }
        awaitCondition { "download:$USER_ID:$FAILING" in activeTags() }
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
        SystemClock.sleep(SHADE_SETTLE_MILLIS)
        screenshot("notification-after-retry")
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
        SystemClock.sleep(SHADE_SETTLE_MILLIS)

        awaitState { state -> FILES.all { state.isAvailableOffline(FilesItemId(it.first)) } }
        screenshot("all-on-this-device")

        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECT_TAG).performClick()
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECTION_DELETE_TAG).performClick()
        compose.onNodeWithText("Delete ${FILES.size} downloads?").assertExists()
        screenshot("bulk-delete-confirmation")
        compose.onNodeWithTag(MOBILE_DOWNLOADS_REMOVE_CONFIRM_TAG).performClick()
        awaitState { it.entries.isEmpty() }
        assertTrue(manager.currentDownloads.isEmpty())
        screenshot("after-bulk-delete")
    }

    private fun DownloadsState.running(): Int = entries.count { it.status is DownloadStatus.Downloading }

    private fun request(fileId: Long, source: File): DownloadRequest =
        DownloadRequest.Builder(downloadContentId(USER_ID, FilesItemId(fileId)), Uri.fromFile(source)).build()

    private fun activeTags(): Set<String> =
        context.getSystemService(NotificationManager::class.java).activeNotifications.mapNotNullTo(mutableSetOf()) {
            it.tag
        }

    private fun awaitState(condition: (DownloadsState) -> Boolean) = awaitCondition {
        compose.waitForIdle()
        condition(controller.state.value)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Condition not met within $TIMEOUT_MILLIS ms" }
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun screenshot(label: String) {
        val runId = UUID.fromString(arguments.getString("putio.downloads.queue.runId"))
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "downloads-queue-proof-$runId")
        check(directory.mkdirs() || directory.isDirectory)
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private inner class ProofDatabase : SQLiteOpenHelper(context, DATABASE, null, 1) {
        override fun onCreate(db: SQLiteDatabase) = Unit

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    /** Local bytes at a steady rate; the failing file reports a full disk until it is retried. */
    private inner class ProofSource : DataSource {
        private val file = FileDataSource()

        override fun addTransferListener(transferListener: TransferListener) =
            file.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            if (failing && dataSpec.uri.lastPathSegment == "$FAILING.mp4") {
                throw IOException("write failed: ENOSPC (No space left on device)")
            }
            return file.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            SystemClock.sleep(CHUNK_DELAY_MILLIS)
            return file.read(buffer, offset, minOf(length, CHUNK_BYTES))
        }

        override fun getUri(): Uri? = file.uri

        override fun close() = file.close()
    }

    private companion object {
        const val USER_ID = 900_002L
        const val FAILING = 4_303L
        val FailingId = FilesItemId(FAILING)
        val FILES = listOf(
            4_301L to "Queue proof — one.mp4",
            4_302L to "Queue proof — two.mp4",
            FAILING to "Queue proof — needs space.mp4",
            4_304L to "Queue proof — four.mp4",
            4_305L to "Queue proof — five.mp4",
        )
        const val DATABASE = "downloads-queue-proof.db"
        const val SOURCE_BYTES = 3 * 1024 * 1024
        const val CHUNK_BYTES = 64 * 1024
        const val CHUNK_DELAY_MILLIS = 50L
        const val TIMEOUT_MILLIS = 90_000L
        const val POLL_MILLIS = 200L
        const val SHADE_SETTLE_MILLIS = 1_500L
    }
}
