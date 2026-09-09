package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
            assertTrue(controller.dispatch(DownloadsEvent.RequestRemoval(video.fileId)))
            assertEquals(DownloadRemoval(video.fileId, video.name), controller.state.value.removal)
            assertTrue(controller.dispatch(DownloadsEvent.CancelRemoval))
            assertNull(controller.state.value.removal)

            assertTrue(controller.dispatch(DownloadsEvent.RequestRemoval(video.fileId)))
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

    suspend fun finishRemoval(fileId: FilesItemId, store: FakeDownloadStore) {
        removing -= fileId
        store.remove(fileId)
        version.value += 1
    }

    suspend fun await(predicate: (FakeDownloadEngine) -> Boolean) {
        withTimeout(5_000L) { version.first { predicate(this@FakeDownloadEngine) } }
    }
}
