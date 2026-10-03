package io.putdotio.android

import android.content.Context
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
import androidx.compose.ui.test.onNodeWithText
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
import io.putdotio.android.downloads.DownloadEntry
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.MobileDownloadCache
import io.putdotio.android.downloads.MobileDownloadEngine
import io.putdotio.android.downloads.MobileDownloadStore
import io.putdotio.android.downloads.MobileDownloadsScreen
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.dispatch
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * The production Downloads screen, controller and engine over a real Media3
 * manager and progressive downloader. The source is a generated local file read
 * at about 1 MB/s, so no API call, account or session is involved.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class MobileDownloadsProgressProofTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Downloads progress proof requires opt-in",
                    arguments.getString("putio.downloads.progress.enabled") == "true")
                UUID.fromString(requireNotNull(arguments.getString("putio.downloads.progress.runId")))
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    private val workDirectory = File(context.cacheDir, "downloads-progress-proof")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cleanup: () -> Unit = {}

    @After
    fun tearDown() {
        cleanup()
        scope.cancel()
        context.deleteDatabase(DATABASE)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit(commit = true) { clear() }
        workDirectory.deleteRecursively()
    }

    @Test
    fun rowAdvancesWhileTheScreenIsShown() {
        check(workDirectory.mkdirs() || workDirectory.isDirectory)
        val source = File(workDirectory, "source.mp4").apply { writeBytes(Random(SEED).nextBytes(SOURCE_BYTES)) }
        val store = MobileDownloadStore(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
            "user-$USER_ID",
        )
        runBlocking {
            store.upsert(DownloadEntry(FILE_ID, FILE_NAME, PutioFileType.VIDEO, DownloadArtifact.ORIGINAL,
                DownloadStatus.Queued, createdAt = 1L, accepted = true))
        }
        lateinit var controller: DownloadsController
        instrumentation.runOnMainSync {
            val database = DefaultDatabaseProvider(ProofDatabase())
            val cache = SimpleCache(File(workDirectory, "cache"), NoOpCacheEvictor(), database)
            val executor = Executors.newSingleThreadExecutor()
            // Seeded before the manager exists: reconcile drops an accepted row its index read cannot see.
            val index = DefaultDownloadIndex(database).apply {
                putDownload(Download(
                    DownloadRequest.Builder("$USER_ID:${FILE_ID.value}", Uri.fromFile(source)).build(),
                    Download.STATE_QUEUED, 0L, 0L, C.LENGTH_UNSET.toLong(), Download.STOP_REASON_NONE,
                    Download.FAILURE_REASON_NONE,
                ))
            }
            val manager = DownloadManager(
                context,
                index,
                DefaultDownloaderFactory(
                    CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { ThrottledFileSource() },
                    executor,
                ),
            ).apply { requirements = Requirements(0) }
            val engine = MobileDownloadEngine(
                context, store, USER_ID, scope, MobileDownloadCache.get(context), manager,
            )
            controller = DownloadsController(store, engine, scope)
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

        val seen = sortedSetOf<Long>()
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
        var captured = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            val status = checkNotNull(controller.state.value.entry(FILE_ID)) { "The row left the store" }.status
            if (status is DownloadStatus.Completed) break
            if (status is DownloadStatus.Downloading && seen.add(status.bytesDownloaded) && captured < SCREENSHOTS &&
                status.bytesDownloaded > 0L
            ) {
                screenshot("downloading-${++captured}")
            }
            SystemClock.sleep(POLL_MILLIS)
        }
        compose.waitForIdle()
        compose.onNodeWithText("On this device", substring = true).assertExists()
        screenshot("completed")
        // Transitions alone would show 0 % and then Completed; polling shows the bytes in between.
        assertTrue("Only saw $seen", seen.count { it in 1L until SOURCE_BYTES.toLong() } >= MIN_PROGRESS_STEPS)
    }

    private fun screenshot(label: String) {
        val runId = UUID.fromString(arguments.getString("putio.downloads.progress.runId"))
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "downloads-progress-proof-$runId")
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

    /** Local bytes at a steady rate, so the transfer spans several poll ticks. */
    private class ThrottledFileSource : DataSource {
        private val file = FileDataSource()

        override fun addTransferListener(transferListener: TransferListener) =
            file.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long = file.open(dataSpec)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            SystemClock.sleep(CHUNK_DELAY_MILLIS)
            return file.read(buffer, offset, minOf(length, CHUNK_BYTES))
        }

        override fun getUri(): Uri? = file.uri

        override fun close() = file.close()
    }

    private companion object {
        const val USER_ID = 900_001L
        val FILE_ID = FilesItemId(4_242L)
        const val FILE_NAME = "Sintel — progress proof.mp4"
        const val DATABASE = "downloads-progress-proof.db"
        const val PREFERENCES = "downloads-progress-proof"
        const val SEED = 186
        const val SOURCE_BYTES = 12 * 1024 * 1024
        const val CHUNK_BYTES = 64 * 1024
        const val CHUNK_DELAY_MILLIS = 60L
        const val TIMEOUT_MILLIS = 90_000L
        const val POLL_MILLIS = 250L
        const val SCREENSHOTS = 3
        const val MIN_PROGRESS_STEPS = 5
    }
}
