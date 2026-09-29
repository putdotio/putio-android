package io.putdotio.android.downloads

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.core.content.IntentCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives the engine against a real Media3 manager and index; only the byte
 * transfer is scripted, so stop reasons, reconcile and progress go through Media3.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileDownloadEngineTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val downloads = MobileDownloadCache.get(context)
    private val index = DefaultDownloadIndex(StandaloneDatabaseProvider(context), "EngineTest")
    private val downloaders = ScriptedDownloaders()
    private val preferences = context.getSharedPreferences("engine-test", Context.MODE_PRIVATE)
    // Main.immediate plus an unconfined index read runs reconcile inside the constructor, as a test step.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val managers = mutableListOf<DownloadManager>()
    private val engines = mutableListOf<MobileDownloadEngine>()

    @After
    fun tearDown() {
        engines.forEach { it.close() }
        managers.forEach { it.release() }
        scope.cancel()
        downloaders.releaseAll()
    }

    @Test
    fun reconcileMirrorsThisUsersIndexAndParksEveryOtherAccount() {
        index.putDownload(download("$ALICE:10", Download.STATE_COMPLETED, bytes = TOTAL))
        index.putDownload(download("$ALICE:11", Download.STATE_STOPPED, stopReason = PARKED))
        index.putDownload(download("$BOB:20", Download.STATE_QUEUED))
        val store = store(ALICE)
        runBlocking {
            store.upsert(entry(10L, accepted = true))
            store.upsert(entry(11L, accepted = true))
            store.upsert(entry(12L, accepted = true))
            store.upsert(entry(13L, accepted = false))
        }
        val manager = manager()

        engine(ALICE, store, manager)

        awaitMain { manager.stopReasonOf("$BOB:20") == PARKED && manager.stopReasonOf("$ALICE:11") == 0 }
        awaitMain { store.status(10L) == DownloadStatus.Completed(TOTAL) }
        // Media3 never saw an accepted row: its bytes are gone. It never saw an unaccepted one: re-issue it.
        assertNull(store.find(FilesItemId(12L)))
        val reissued = shadowOf(context).nextStartedService
        assertEquals(MobileDownloadService::class.java.name, reissued.component?.className)
        assertEquals("$ALICE:13", IntentCompat.getParcelableExtra(reissued, DownloadService.KEY_DOWNLOAD_REQUEST, DownloadRequest::class.java)?.id)
        assertTrue(store.entries.value.none { it.fileId.value == 20L })
        assertEquals(PARKED, index.getDownload("$BOB:20")?.stopReason)
    }

    @Test
    fun signOutParksThisUsersTransfersUntilTheOwnerSignsInAgain() {
        index.putDownload(download("$ALICE:10", Download.STATE_QUEUED))
        val aliceStore = store(ALICE)
        runBlocking { aliceStore.upsert(entry(10L, accepted = true)) }
        val manager = manager()
        val alice = engine(ALICE, aliceStore, manager)
        awaitMain { manager.stateOf("$ALICE:10") == Download.STATE_DOWNLOADING }

        // The auth layer runs this hook before it clears the token.
        checkNotNull(downloads.onTokenClearing).invoke()
        alice.close()
        awaitMain { manager.stateOf("$ALICE:10") == Download.STATE_STOPPED }
        assertEquals(PARKED, manager.stopReasonOf("$ALICE:10"))
        assertNull(downloads.onTokenClearing)

        // Another account signing in leaves the transfer parked and never sees the row.
        val bobStore = store(BOB)
        engine(BOB, bobStore, manager).close()
        awaitMain { manager.isIdle }
        assertEquals(PARKED, manager.stopReasonOf("$ALICE:10"))
        assertTrue(bobStore.entries.value.isEmpty())

        engine(ALICE, store(ALICE), manager)
        awaitMain { manager.stateOf("$ALICE:10") == Download.STATE_DOWNLOADING }
        assertEquals(0, manager.stopReasonOf("$ALICE:10"))
    }

    @Test
    fun closeBeforeTheIndexReadLandsKeepsParkedTransfersParked() {
        index.putDownload(download("$ALICE:10", Download.STATE_STOPPED, stopReason = PARKED))
        val store = store(ALICE)
        runBlocking { store.upsert(entry(10L, accepted = true)) }
        val manager = manager()
        awaitMain { manager.isInitialized }
        val io = StandardTestDispatcher()

        val engine = engine(ALICE, store, manager, io)
        shadowOf(Looper.getMainLooper()).idle()
        engine.close()
        io.scheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        awaitMain { manager.isIdle || manager.stopReasonOf("$ALICE:10") != PARKED }
        assertEquals(PARKED, manager.stopReasonOf("$ALICE:10"))
        assertEquals(Download.STATE_STOPPED, manager.stateOf("$ALICE:10"))
    }

    @Test
    fun visibleProgressAdvancesInMemoryAndOnlyTransitionsPersist() {
        index.putDownload(download("$ALICE:10", Download.STATE_QUEUED))
        val store = store(ALICE)
        runBlocking { store.upsert(entry(10L, accepted = true)) }
        val manager = manager()
        val engine = engine(ALICE, store, manager)
        val transfer = downloaders.await("$ALICE:10")
        awaitMain { store.status(10L) == DownloadStatus.Downloading(0L, TOTAL) }
        val persisted = preferences.getString(storeKey(ALICE), null)

        transfer.report(bytes = 600L)
        awaitMain {
            engine.refreshProgress()
            store.status(10L) == DownloadStatus.Downloading(600L, TOTAL)
        }
        assertEquals(persisted, preferences.getString(storeKey(ALICE), null))

        transfer.finish()
        awaitMain { store.status(10L) is DownloadStatus.Completed }
        engine.refreshProgress()
        assertTrue(store.status(10L) is DownloadStatus.Completed)
        assertFalse(preferences.getString(storeKey(ALICE), null) == persisted)
    }

    private fun manager(): DownloadManager =
        DownloadManager(context, index, downloaders).apply {
            requirements = Requirements(0)
            maxParallelDownloads = 1
            managers += this
        }

    private fun engine(
        userId: Long,
        store: MobileDownloadStore,
        manager: DownloadManager,
        io: CoroutineDispatcher = Dispatchers.Unconfined,
    ): MobileDownloadEngine =
        MobileDownloadEngine(context, store, userId, scope, downloads, manager, io).also { engines += it }

    private fun store(userId: Long) = MobileDownloadStore(preferences, storeKey(userId), Dispatchers.Unconfined)

    private fun storeKey(userId: Long) = "user-$userId"

    private fun MobileDownloadStore.status(fileId: Long): DownloadStatus? = find(FilesItemId(fileId))?.status

    private fun DownloadManager.stopReasonOf(id: String): Int? = currentDownloads.firstOrNull { it.request.id == id }
        ?.stopReason

    private fun DownloadManager.stateOf(id: String): Int? = currentDownloads.firstOrNull { it.request.id == id }?.state

    /** Media3 answers on the main looper after its own thread works, so idle it until the condition holds. */
    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.nanoTime() < deadline) { "Condition not met within $AWAIT_SECONDS s" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun entry(fileId: Long, accepted: Boolean) = DownloadEntry(
        FilesItemId(fileId), "file-$fileId", PutioFileType.VIDEO, DownloadArtifact.HLS,
        DownloadStatus.Queued, createdAt = fileId, accepted = accepted,
    )

    private fun download(id: String, state: Int, stopReason: Int = 0, bytes: Long = 0L) = Download(
        DownloadRequest.Builder(id, Uri.parse("https://api.put.io/v2/files/$id/stream")).build(),
        state,
        0L,
        0L,
        TOTAL,
        stopReason,
        Download.FAILURE_REASON_NONE,
        DownloadProgress().apply {
            bytesDownloaded = bytes
            percentDownloaded = bytes * PERCENT / TOTAL
        },
    )

    private companion object {
        const val ALICE = 1L
        const val BOB = 2L
        const val PARKED = MobileDownloadEngine.STOP_REASON_OTHER_USER
        const val TOTAL = 1_000L
        const val PERCENT = 100f
        const val AWAIT_SECONDS = 10L
        const val POLL_MILLIS = 10L
    }
}

/** One transfer per request id: it reports only the bytes the test feeds and blocks until cancelled or finished. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class ScriptedDownloaders : DownloaderFactory {
    private val transfers = ConcurrentHashMap<String, LinkedBlockingQueue<ScriptedTransfer>>()

    override fun createDownloader(request: DownloadRequest): Downloader =
        ScriptedTransfer().also { queue(request.id).put(it) }

    fun await(id: String): ScriptedTransfer = checkNotNull(queue(id).poll(10L, TimeUnit.SECONDS)) {
        "No transfer started for $id"
    }

    fun releaseAll() = transfers.values.forEach { queue -> queue.forEach { it.cancel() } }

    private fun queue(id: String) = transfers.getOrPut(id) { LinkedBlockingQueue() }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class ScriptedTransfer : Downloader {
    private val steps = LinkedBlockingQueue<Long>()
    private val released = CountDownLatch(1)

    fun report(bytes: Long) = steps.put(bytes)

    fun finish() = steps.put(DONE)

    override fun download(progressListener: Downloader.ProgressListener?) {
        while (released.count > 0L) {
            val bytes = steps.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
            if (bytes == DONE) return
            progressListener?.onProgress(TOTAL_BYTES, bytes, bytes * PERCENT / TOTAL_BYTES)
        }
        throw InterruptedException("cancelled")
    }

    override fun cancel() = released.countDown()

    override fun remove() = Unit

    private companion object {
        const val DONE = -1L
        const val TOTAL_BYTES = 1_000L
        const val PERCENT = 100f
        const val POLL_MILLIS = 10L
    }
}
