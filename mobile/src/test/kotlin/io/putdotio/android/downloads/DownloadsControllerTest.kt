package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsControllerTest {
    private val video = DownloadRequest(FilesItemId(7L), "Sintel.mkv", PutioFileType.VIDEO, 1_000L)
    private val audio = DownloadRequest(FilesItemId(8L), "Track.flac", PutioFileType.AUDIO, 2_000L)

    @Test
    fun startPersistsQueuedRowThenHandsItToTheEngine() = runBlocking {
        val store = FakeDownloadStore()
        val engine = FakeDownloadEngine()
        var now = 42L
        DownloadsController(store, engine, this) { now++ }.use { controller ->
            assertTrue(controller.dispatch(DownloadsEvent.Start(video)))
            val entry = store.await { it.any { entry -> entry.fileId == video.fileId } }.single()
            assertEquals(DownloadStatus.Queued, entry.status)
            assertEquals(DownloadArtifact.HLS, entry.artifact)
            assertEquals(42L, entry.createdAt)
            engine.await { it.started.size == 1 }
            assertEquals(entry, engine.started.single())

            assertTrue(controller.dispatch(DownloadsEvent.Start(audio)))
            val audioEntry = store.await { it.size == 2 }.first { it.fileId == audio.fileId }
            assertEquals(DownloadArtifact.ORIGINAL, audioEntry.artifact)
            val state = controller.awaitState { it.entries.size == 2 }
            assertEquals(listOf(audio.fileId, video.fileId), state.entries.map { it.fileId })
        }
    }

    @Test
    fun foldersAndDuplicatesAreRejected() = runBlocking {
        val store = FakeDownloadStore()
        val engine = FakeDownloadEngine()
        DownloadsController(store, engine, this).use { controller ->
            assertFalse(controller.dispatch(DownloadsEvent.Start(video.copy(type = PutioFileType.FOLDER))))
            assertTrue(controller.dispatch(DownloadsEvent.Start(video)))
            store.await { it.size == 1 }
            engine.await { it.started.size == 1 }
            assertFalse(controller.dispatch(DownloadsEvent.Start(video)))
            assertEquals(1, engine.started.size)
        }
    }

    @Test
    fun retryOnlyAppliesToFailedRowsAndRequeuesThem() = runBlocking {
        val store = FakeDownloadStore()
        val engine = FakeDownloadEngine()
        DownloadsController(store, engine, this).use { controller ->
            assertTrue(controller.dispatch(DownloadsEvent.Start(video)))
            store.await { it.size == 1 }
            assertFalse(controller.dispatch(DownloadsEvent.Retry(video.fileId)))
            store.fail(video.fileId)
            controller.awaitState { it.entry(video.fileId)?.canRetry == true }
            assertTrue(controller.dispatch(DownloadsEvent.Retry(video.fileId)))
            store.await { it.single().status == DownloadStatus.Queued }
            engine.await { it.started.size == 2 }
        }
    }

    @Test
    fun removalNeedsConfirmationAndClearsEngineThenStore() = runBlocking {
        val store = FakeDownloadStore()
        val engine = FakeDownloadEngine()
        DownloadsController(store, engine, this).use { controller ->
            assertTrue(controller.dispatch(DownloadsEvent.Start(video)))
            store.await { it.size == 1 }
            store.complete(video.fileId, 900L)
            val ready = controller.awaitState { it.isAvailableOffline(video.fileId) }
            assertEquals(900L, ready.storageBytes)

            assertFalse(controller.dispatch(DownloadsEvent.ConfirmRemoval))
            assertTrue(controller.dispatch(DownloadsEvent.RequestRemoval(setOf(video.fileId))))
            assertEquals(DownloadRemoval(setOf(video.fileId), video.name), controller.state.value.removal)
            assertTrue(controller.dispatch(DownloadsEvent.CancelRemoval))
            assertNull(controller.state.value.removal)

            assertTrue(controller.dispatch(DownloadsEvent.RequestRemoval(setOf(video.fileId))))
            assertTrue(controller.dispatch(DownloadsEvent.ConfirmRemoval))
            engine.await { it.removed == listOf(video.fileId) }
            assertNull(controller.state.value.removal)
            // The row stays until the engine confirms; re-download and retry are refused meanwhile.
            assertEquals(1, controller.state.value.entries.size)
            assertTrue(controller.state.value.removing.contains(video.fileId))
            assertFalse(controller.dispatch(DownloadsEvent.Start(video)))
            store.fail(video.fileId)
            controller.awaitState { it.entry(video.fileId)?.canRetry == true }
            assertFalse(controller.dispatch(DownloadsEvent.Retry(video.fileId)))
            engine.finishRemoval(video.fileId, store)
            val cleared = controller.awaitState { it.entries.isEmpty() }
            assertEquals(0L, cleared.storageBytes)
            assertTrue(cleared.removing.isEmpty())
            assertTrue(controller.dispatch(DownloadsEvent.Start(video)))
        }
    }

    @Test
    fun bulkRemovalDeletesOnlyTheLocalCopiesAndMarksThemBeforeTheEngineRuns() = runBlocking<Unit> {
        val store = FakeDownloadStore()
        val engine = FakeDownloadEngine()
        val third = video.copy(fileId = FilesItemId(9L), name = "Tears.mkv")
        DownloadsController(store, engine, this).use { controller ->
            for (request in listOf(video, audio, third)) assertTrue(controller.dispatch(DownloadsEvent.Start(request)))
            store.await { it.size == 3 }
            store.complete(video.fileId, 10L)
            store.complete(audio.fileId, 20L)
            controller.awaitState { it.isAvailableOffline(video.fileId) && it.isAvailableOffline(audio.fileId) }
            val all = setOf(video.fileId, audio.fileId, third.fileId)

            assertTrue(controller.dispatch(DownloadsEvent.RequestRemoval(all + FilesItemId(404L))))
            // Several rows have no single name to show; an unknown id is not part of the question.
            assertEquals(DownloadRemoval(all, null), controller.state.value.removal)
            assertTrue(controller.dispatch(DownloadsEvent.ConfirmRemoval))

            // The local copies stop counting at once, before Media3 confirms.
            val removing = controller.state.value
            assertTrue(all.none { removing.isAvailableOffline(it) || removing.rowStatus(it) != null })
            engine.await { it.removed.toSet() == all }
            assertTrue(store.entries.value.all { it.removing })
            for (fileId in all) engine.finishRemoval(fileId, store)
            controller.awaitState { it.entries.isEmpty() && it.removing.isEmpty() }
        }
    }

    @Test
    fun retriedAndMissingRowsJoinTheBackOfTheQueue() = runBlocking {
        val store = FakeDownloadStore()
        val engine = FakeDownloadEngine()
        var now = 100L
        DownloadsController(store, engine, this) { now++ }.use { controller ->
            assertTrue(controller.dispatch(DownloadsEvent.Start(video)))
            assertTrue(controller.dispatch(DownloadsEvent.Start(audio)))
            store.await { it.size == 2 }
            store.fail(video.fileId)
            controller.awaitState { it.queue.map { entry -> entry.fileId } == listOf(audio.fileId) }

            assertTrue(controller.dispatch(DownloadsEvent.Retry(video.fileId)))
            val requeued = controller.awaitState { it.queue.size == 2 }
            assertEquals(listOf(audio.fileId, video.fileId), requeued.queue.map { it.fileId })
            assertEquals(1, requeued.queuePosition(audio.fileId))
            assertEquals(2, requeued.queuePosition(video.fileId))
        }
    }

    @Test
    fun aMissingCopyStopsCountingAsOfflineAndCanBeDownloadedAgain() = runBlocking {
        val store = FakeDownloadStore()
        val engine = FakeDownloadEngine()
        DownloadsController(store, engine, this).use { controller ->
            assertTrue(controller.dispatch(DownloadsEvent.Start(video)))
            store.await { it.size == 1 }
            store.complete(video.fileId, 10L)
            controller.awaitState { it.isAvailableOffline(video.fileId) }

            assertTrue(controller.dispatch(DownloadsEvent.LocalCopyMissing(video.fileId)))
            val missing = controller.awaitState { it.entry(video.fileId)?.status == DownloadStatus.Missing }
            assertFalse(missing.isAvailableOffline(video.fileId))
            assertEquals(listOf(video.fileId), missing.needsAttention.map { it.fileId })

            assertTrue(controller.dispatch(DownloadsEvent.Retry(video.fileId)))
            engine.await { it.started.size == 2 }
            assertEquals(DownloadStatus.Queued, store.entries.value.single().status)
        }
    }

    @Test
    fun theConcurrencyChoiceReachesTheEngineWithinTheOfferedRange() = runBlocking {
        val engine = FakeDownloadEngine()
        DownloadsController(FakeDownloadStore(), engine, this).use { controller ->
            assertEquals(DOWNLOAD_CONCURRENCY_DEFAULT, controller.state.value.concurrency)
            assertFalse(controller.dispatch(DownloadsEvent.SetConcurrency(0)))
            assertFalse(controller.dispatch(DownloadsEvent.SetConcurrency(5)))
            assertFalse(controller.dispatch(DownloadsEvent.SetConcurrency(DOWNLOAD_CONCURRENCY_DEFAULT)))
            assertTrue(controller.dispatch(DownloadsEvent.SetConcurrency(1)))
            engine.await { it.concurrency == 1 }
            assertEquals(1, controller.state.value.concurrency)
        }
    }

    @Test
    fun aStartCarriesTheConfirmedSubtitleSettingAndSavedPosition() = runBlocking {
        val store = FakeDownloadStore()
        val engine = FakeDownloadEngine()
        DownloadsController(store, engine, this).use { controller ->
            val request = video.copy(subtitlesHidden = true, startFromSeconds = 42.0, durationSeconds = 600.0)
            assertTrue(controller.dispatch(DownloadsEvent.Start(request)))
            engine.await { it.started.size == 1 }
            val started = engine.started.single()
            assertEquals(true, started.subtitlesHidden)
            assertEquals(42.0, started.startFromSeconds, 0.0)
            assertEquals(600.0, started.durationSeconds)
        }
    }

    @Test
    fun progressRefreshesEverySecondOnlyWhileTheScreenIsShown() = runTest {
        val engine = FakeDownloadEngine()
        val controller = DownloadsController(FakeDownloadStore(), engine, backgroundScope)
        runCurrent()
        advanceTimeBy(3_000L)
        assertEquals(0, engine.progressRefreshes)

        assertTrue(controller.dispatch(DownloadsEvent.Shown))
        assertFalse(controller.dispatch(DownloadsEvent.Shown))
        runCurrent()
        assertEquals(1, engine.progressRefreshes)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(3, engine.progressRefreshes)

        assertTrue(controller.dispatch(DownloadsEvent.Hidden))
        advanceTimeBy(5_000L)
        assertEquals(3, engine.progressRefreshes)

        assertTrue(controller.dispatch(DownloadsEvent.Shown))
        runCurrent()
        controller.close()
        advanceTimeBy(5_000L)
        assertEquals(4, engine.progressRefreshes)
    }

    private suspend fun DownloadsController.awaitState(predicate: (DownloadsState) -> Boolean): DownloadsState =
        withTimeout(5_000L) { state.first(predicate) }
}

internal class FakeDownloadStore : DownloadStore {
    private val mutable = MutableStateFlow<List<DownloadEntry>>(emptyList())
    override val entries: StateFlow<List<DownloadEntry>> = mutable

    override suspend fun upsert(entry: DownloadEntry) {
        mutable.value = mutable.value.filterNot { it.fileId == entry.fileId } + entry
    }

    override suspend fun remove(fileId: FilesItemId) {
        mutable.value = mutable.value.filterNot { it.fileId == fileId }
    }

    fun fail(fileId: FilesItemId) = transform(fileId) {
        it.copy(status = DownloadStatus.Failed(DownloadFailureReason.NETWORK, 10L))
    }

    fun complete(fileId: FilesItemId, bytes: Long) = transform(fileId) {
        it.copy(status = DownloadStatus.Completed(bytes))
    }

    private fun transform(fileId: FilesItemId, block: (DownloadEntry) -> DownloadEntry) {
        mutable.value = mutable.value.map { if (it.fileId == fileId) block(it) else it }
    }

    suspend fun await(predicate: (List<DownloadEntry>) -> Boolean): List<DownloadEntry> =
        withTimeout(5_000L) { entries.first(predicate) }
}

internal class FakeDownloadEngine : DownloadEngine {
    val started = mutableListOf<DownloadEntry>()
    val removed = mutableListOf<FilesItemId>()
    override var concurrency: Int = DOWNLOAD_CONCURRENCY_DEFAULT
        private set
    var progressRefreshes = 0
        private set
    private val removing = mutableSetOf<FilesItemId>()
    private val version = MutableStateFlow(0)

    override fun start(entry: DownloadEntry) {
        started += entry
        version.value += 1
    }

    override fun remove(fileId: FilesItemId) {
        removed += fileId
        removing += fileId
        version.value += 1
    }

    override fun isRemoving(fileId: FilesItemId): Boolean = fileId in removing

    override fun setConcurrency(limit: Int) {
        concurrency = limit
        version.value += 1
    }

    override fun refreshProgress() {
        progressRefreshes += 1
    }

    suspend fun finishRemoval(fileId: FilesItemId, store: FakeDownloadStore) {
        removing -= fileId
        store.remove(fileId)
        version.value += 1
    }

    suspend fun await(predicate: (FakeDownloadEngine) -> Boolean) {
        withTimeout(5_000L) { version.first { predicate(this@FakeDownloadEngine) } }
    }
}
