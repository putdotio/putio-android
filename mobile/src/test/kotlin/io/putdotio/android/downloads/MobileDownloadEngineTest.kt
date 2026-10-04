package io.putdotio.android.downloads

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.core.content.IntentCompat
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
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
    private val cachedKeys = mutableListOf<String>()

    @After
    fun tearDown() {
        engines.forEach { it.close() }
        managers.forEach { it.release() }
        scope.cancel()
        downloaders.releaseAll()
        downloadPreferences(context).edit().clear().commit()
        cachedKeys.forEach(downloads.cache::removeResource)
    }

    @Test
    fun reconcileMirrorsThisUsersIndexAndParksEveryOtherAccount() {
        index.putDownload(download("$ALICE:10", Download.STATE_COMPLETED, bytes = TOTAL).also(::cacheBytes))
        index.putDownload(download("$ALICE:11", Download.STATE_STOPPED, stopReason = PARKED))
        index.putDownload(download("$BOB:20", Download.STATE_QUEUED))
        val store = store(ALICE)
        runBlocking {
            store.upsert(entry(10L, accepted = true))
            store.upsert(entry(11L, accepted = true))
            store.upsert(entry(13L, accepted = false).copy(subtitlesHidden = true))
        }
        val manager = manager()

        engine(ALICE, store, manager)

        awaitMain { manager.stopReasonOf("$BOB:20") == PARKED && manager.stopReasonOf("$ALICE:11") == 0 }
        awaitMain { store.status(10L) == DownloadStatus.Completed(TOTAL) }
        // Media3 never saw this request: it goes out again, skipping subtitles for a hide_subtitles account.
        val request = startedRequest()
        assertEquals("$ALICE:13", request.id)
        assertEquals("0", request.uri.getQueryParameter("max_subtitle_count"))
        assertTrue(store.entries.value.none { it.fileId.value == 20L })
        assertEquals(PARKED, index.getDownload("$BOB:20")?.stopReason)
    }

    @Test
    fun everyRowRecoversToAStateTheViewerCanActOnAndOnlyConfirmedDeletesLeave() {
        index.putDownload(download("$ALICE:10", Download.STATE_COMPLETED, bytes = TOTAL).also(::cacheBytes))
        // Media3 still lists it, but its bytes are gone from the cache.
        index.putDownload(download("$ALICE:11", Download.STATE_COMPLETED, bytes = TOTAL))
        index.putDownload(download("$ALICE:15", Download.STATE_COMPLETED, bytes = TOTAL).also(::cacheBytes))
        val store = store(ALICE)
        runBlocking {
            store.upsert(entry(10L, accepted = true, status = DownloadStatus.Completed(TOTAL)))
            store.upsert(entry(11L, accepted = true, status = DownloadStatus.Completed(TOTAL)))
            // Interrupted mid-transfer, and Media3 lost the request.
            store.upsert(entry(12L, accepted = true, status = DownloadStatus.Downloading(5L, 1f)))
            store.upsert(entry(13L, accepted = true, status = DownloadStatus.Failed(DownloadFailureReason.STORAGE, 9L)))
            // Finished, then Media3's record went with the bytes.
            store.upsert(entry(14L, accepted = true, status = DownloadStatus.Completed(TOTAL)))
            // Deletes confirmed before the process died: one Media3 finished, one it never heard of.
            store.upsert(entry(16L, accepted = true, status = DownloadStatus.Completed(TOTAL)).copy(removing = true))
            store.upsert(entry(15L, accepted = true, status = DownloadStatus.Completed(TOTAL)).copy(removing = true))
        }
        val reloaded = store(ALICE)
        val manager = manager()

        engine(ALICE, reloaded, manager)

        awaitMain { reloaded.status(11L) == DownloadStatus.Missing }
        assertEquals(DownloadStatus.Completed(TOTAL), reloaded.status(10L))
        assertEquals(DownloadStatus.Missing, reloaded.status(14L))
        assertEquals(DownloadStatus.Failed(DownloadFailureReason.STORAGE, 9L), reloaded.status(13L))
        assertEquals(DownloadStatus.Queued, reloaded.status(12L))
        assertNull(reloaded.find(FilesItemId(16L)))
        assertTrue(reloaded.find(FilesItemId(15L))?.removing == true)
        val sent = generateSequence { shadowOf(context).nextStartedService }.toList()
        assertEquals(
            listOf(
                "$ALICE:12" to DownloadService.ACTION_ADD_DOWNLOAD,
                "$ALICE:15" to DownloadService.ACTION_REMOVE_DOWNLOAD,
            ),
            sent.map { intent ->
                val request = IntentCompat
                    .getParcelableExtra(intent, DownloadService.KEY_DOWNLOAD_REQUEST, DownloadRequest::class.java)
                (intent.getStringExtra(DownloadService.KEY_CONTENT_ID) ?: request?.id) to intent.action
            },
        )
    }

    @Test
    fun downloadsAnUnreadableIndexLostComeBackAsRowsTheViewerCanPlayAndDelete() {
        val hls = Download(
            DownloadRequest.Builder(
                "$ALICE:30",
                Uri.parse("https://api.put.io/v2/files/30/hls/media.m3u8?subtitle_key=all&max_subtitle_count=0"),
            ).setMimeType(MimeTypes.APPLICATION_M3U8).setData("Sintel.mkv".encodeToByteArray()).build(),
            Download.STATE_COMPLETED, 5L, 5L, TOTAL, Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE,
            DownloadProgress().apply { bytesDownloaded = TOTAL },
        )
        index.putDownload(hls.also(::cacheBytes))
        index.putDownload(download("$ALICE:31", Download.STATE_STOPPED, stopReason = PARKED))
        index.putDownload(download("$BOB:40", Download.STATE_QUEUED))
        preferences.edit().putString(storeKey(ALICE), "[{not json").commit()
        val store = store(ALICE)
        assertTrue(store.entries.value.isEmpty())
        val manager = manager()

        val engine = engine(ALICE, store, manager)

        awaitMain { store.entries.value.size == 2 }
        val video = checkNotNull(store.find(FilesItemId(30L)))
        assertEquals("Sintel.mkv", video.name)
        assertEquals(PutioFileType.VIDEO to DownloadArtifact.HLS, video.type to video.artifact)
        assertEquals(DownloadStatus.Completed(TOTAL), video.status)
        assertEquals(true, video.subtitlesHidden)
        assertTrue(video.recovered)
        val audio = checkNotNull(store.find(FilesItemId(31L)))
        assertEquals("Downloaded file", audio.name)
        assertEquals(PutioFileType.AUDIO to DownloadArtifact.ORIGINAL, audio.type to audio.artifact)
        assertTrue(audio.isActive)
        assertTrue(store.entries.value.none { it.fileId.value == 40L })
        // Nothing is deleted on the way: bytes leave only through a confirmed delete.
        val removals = generateSequence { shadowOf(context).nextStartedService }
            .filter { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD }.toList()
        assertTrue(removals.isEmpty())

        val controller = DownloadsController(store, engine, scope)
        assertTrue(controller.dispatch(DownloadsEvent.RequestRemoval(setOf(FilesItemId(30L)))))
        assertTrue(controller.dispatch(DownloadsEvent.ConfirmRemoval))
        awaitMain {
            shadowOf(context).nextStartedService?.getStringExtra(DownloadService.KEY_CONTENT_ID) == "$ALICE:30"
        }
        // The service hands the remove to Media3; the row leaves once Media3 confirms.
        manager.removeDownload("$ALICE:30")
        awaitMain { store.find(FilesItemId(30L)) == null }
        controller.close()
    }

    @Test
    fun queueOrderAndConcurrencySurviveProcessRecreation() {
        for (id in 10L..13L) index.putDownload(queued("$ALICE:$id", startTimeMs = id))
        val store = store(ALICE)
        // Newest-first creation times: only Media3's start times can put these rows in order.
        runBlocking {
            for (id in 10L..13L) store.upsert(entry(id, accepted = true).copy(createdAt = 100L - id, queuedAt = 0L))
        }
        val first = manager(MobileDownloadSettings(context).concurrency)
        val engine = engine(ALICE, store, first)
        awaitMain { first.running() == listOf("$ALICE:10", "$ALICE:11", "$ALICE:12") }

        engine.setConcurrency(2)
        awaitMain { first.running() == listOf("$ALICE:10", "$ALICE:11") }
        awaitMain { queueOf(store) == listOf(10L to null, 11L to null, 12L to 1, 13L to 2) }

        // Process death: the manager and every in-memory row go; the index and preferences stay.
        engine.close()
        first.release()
        val restored = store(ALICE)
        val second = manager(MobileDownloadSettings(context).concurrency)
        engine(ALICE, restored, second)

        awaitMain { second.running() == listOf("$ALICE:10", "$ALICE:11") }
        awaitMain { queueOf(restored) == listOf(10L to null, 11L to null, 12L to 1, 13L to 2) }
        assertEquals(DownloadStatus.Queued, restored.status(12L))
    }

    // Media3 resumes once storage recovers; Robolectric cannot withdraw a sticky broadcast, so the
    // emulator lane proves that half.
    @Test
    fun lowStorageHoldsTransfersWithItsReason() {
        index.putDownload(download("$ALICE:10", Download.STATE_QUEUED))
        val store = store(ALICE)
        runBlocking { store.upsert(entry(10L, accepted = true)) }
        val manager = manager().apply { requirements = Requirements(Requirements.DEVICE_STORAGE_NOT_LOW) }
        engine(ALICE, store, manager)
        downloaders.await("$ALICE:10")
        awaitMain { store.status(10L) is DownloadStatus.Downloading }

        context.sendStickyBroadcast(Intent(Intent.ACTION_DEVICE_STORAGE_LOW))
        awaitMain { store.status(10L) == DownloadStatus.Paused(DownloadPauseReason.STORAGE, 0L) }
    }

    @Test
    fun theLocalCopyCheckReadsTheRequestsOwnCacheKey() {
        val uri = "https://api.put.io/v2/files/77/stream"
        val owned = download("$ALICE:77", Download.STATE_COMPLETED, bytes = 4L, uri = uri)
        assertFalse(downloads.holdsLocalCopy(owned))
        cacheBytes(owned)
        assertTrue(downloads.holdsLocalCopy(owned))
        assertFalse(downloads.holdsLocalCopy(download("$BOB:77", Download.STATE_COMPLETED, uri = uri)))
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
        awaitMain { store.status(10L) == DownloadStatus.Downloading(0L, 0f) }
        val persisted = preferences.getString(storeKey(ALICE), null)

        // Without a denominator the row has bytes and no percentage.
        transfer.report(bytes = 300L, percent = C.PERCENTAGE_UNSET.toFloat())
        awaitMain {
            engine.refreshProgress()
            store.status(10L) == DownloadStatus.Downloading(300L, null)
        }
        transfer.report(bytes = 600L)
        awaitMain {
            engine.refreshProgress()
            store.status(10L) == DownloadStatus.Downloading(600L, 60f)
        }
        assertEquals(persisted, preferences.getString(storeKey(ALICE), null))

        transfer.finish()
        awaitMain { store.status(10L) is DownloadStatus.Completed }
        engine.refreshProgress()
        assertTrue(store.status(10L) is DownloadStatus.Completed)
        assertFalse(preferences.getString(storeKey(ALICE), null) == persisted)
    }

    @Test
    fun offlinePlaybackReadsBackTheUrlEachDownloadWasRequestedWith() {
        val earlier = "https://api.put.io/v2/files/10/hls/media.m3u8?subtitle_key=all"
        index.putDownload(download("$ALICE:10", Download.STATE_COMPLETED, bytes = TOTAL, uri = earlier))

        assertEquals(earlier, index.requestedUrl(ALICE, FilesItemId(10L)))
        assertNull(index.requestedUrl(BOB, FilesItemId(10L)))
        assertNull(index.requestedUrl(ALICE, FilesItemId(11L)))
    }

    private fun manager(concurrency: Int = 1): DownloadManager =
        DownloadManager(context, index, downloaders).apply {
            requirements = Requirements(0)
            maxParallelDownloads = concurrency
            managers += this
        }

    private fun engine(
        userId: Long,
        store: MobileDownloadStore,
        manager: DownloadManager,
        io: CoroutineDispatcher = Dispatchers.Unconfined,
    ): MobileDownloadEngine =
        MobileDownloadEngine(context, store, userId, scope, manager, io).also { engines += it }

    /** Writes a few bytes under the request's own cache key, as a finished download leaves them. */
    private fun cacheBytes(download: Download) {
        val keys = UserScopedCacheKeys(checkNotNull(download.request.ownerUserId()))
        CacheWriter(
            CacheDataSource.Factory().setCache(downloads.cache).setCacheKeyFactory(keys)
                .setUpstreamDataSourceFactory { ByteArrayDataSource(ByteArray(CACHED_BYTES)) }.createDataSource(),
            DataSpec(download.request.uri),
            null,
            null,
        ).cache()
        cachedKeys += keys.buildCacheKey(DataSpec(download.request.uri))
    }

    private fun startedRequest(): DownloadRequest {
        val intent = shadowOf(context).nextStartedService
        assertEquals(MobileDownloadService::class.java.name, intent.component?.className)
        return checkNotNull(
            IntentCompat.getParcelableExtra(intent, DownloadService.KEY_DOWNLOAD_REQUEST, DownloadRequest::class.java),
        )
    }

    private fun DownloadManager.running(): List<String> =
        currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }.map { it.request.id }.sorted()

    /** Each row with its place in line, in the order the Downloads screen lists the queue. */
    private fun queueOf(store: MobileDownloadStore): List<Pair<Long, Int?>> {
        val state = DownloadsState().withEntries(store.entries.value)
        return state.queue.map { it.fileId.value to state.queuePosition(it.fileId) }
    }

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

    private fun entry(fileId: Long, accepted: Boolean, status: DownloadStatus = DownloadStatus.Queued) = DownloadEntry(
        FilesItemId(fileId), "file-$fileId", PutioFileType.VIDEO, DownloadArtifact.HLS,
        status, createdAt = fileId, accepted = accepted,
    )

    private fun download(
        id: String,
        state: Int,
        stopReason: Int = 0,
        bytes: Long = 0L,
        uri: String = "https://api.put.io/v2/files/$id/stream",
    ) = Download(
        DownloadRequest.Builder(id, Uri.parse(uri)).build(),
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

    private fun queued(id: String, startTimeMs: Long) = Download(
        DownloadRequest.Builder(id, Uri.parse("https://api.put.io/v2/files/$id/stream")).build(),
        Download.STATE_QUEUED,
        startTimeMs,
        startTimeMs,
        TOTAL,
        Download.STOP_REASON_NONE,
        Download.FAILURE_REASON_NONE,
    )

    private companion object {
        const val ALICE = 1L
        const val BOB = 2L
        const val PARKED = MobileDownloadEngine.STOP_REASON_OTHER_USER
        const val TOTAL = 1_000L
        const val CACHED_BYTES = 4
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
    private val released = CountDownLatch(1)

    private val steps = LinkedBlockingQueue<Pair<Long, Float>>()

    fun report(bytes: Long, percent: Float = bytes * PERCENT / TOTAL_BYTES) = steps.put(bytes to percent)

    fun finish() = steps.put(DONE to 0f)

    override fun download(progressListener: Downloader.ProgressListener?) {
        while (released.count > 0L) {
            val (bytes, percent) = steps.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
            if (bytes == DONE) return
            progressListener?.onProgress(TOTAL_BYTES, bytes, percent)
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
