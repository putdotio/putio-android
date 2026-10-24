package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileContinueWatchingTest {
    private val store = MemoryStore()
    private var now = 1_000L
    private val row = MobileContinueWatching(store, clock = { now++ })

    @Test
    fun aSavedPositionOfAnOpenedVideoJoinsNewestFirstAndFinishingRemovesIt() {
        row.opened(Tears, "Tears.of.Steel.mkv", 734.0)
        row.opened(Sintel, "Sintel.mkv", 888.0)
        // A position for a video the player never resolved has no name or duration to show.
        row.positionSaved(FilesItemId(99L), 30.0)
        row.positionSaved(Tears, 482.0)
        row.positionSaved(Sintel, 353.0)

        assertEquals(listOf(Sintel, Tears), row.entries.value.map { it.fileId })
        assertEquals(252L, row.entries.value.last().remainingSeconds)
        assertEquals(row.entries.value, store.saved)

        // Within 10 s of the end counts as finished, as the player's start-over rule does.
        row.positionSaved(Tears, 725.0)
        assertEquals(listOf(Sintel), row.entries.value.map { it.fileId })
        // An unwatched reset leaves too.
        row.positionSaved(Sintel, 0.0)
        assertTrue(row.entries.value.isEmpty())
    }

    @Test
    fun aVideoWithoutADurationNeverJoins() {
        row.opened(Tears, "Tears.of.Steel.mkv", null)
        row.positionSaved(Tears, 60.0)

        assertTrue(row.entries.value.isEmpty())
    }

    @Test
    fun keepsTheTenMostRecent() {
        (1L..12L).forEach { id ->
            row.opened(FilesItemId(id), "video-$id.mkv", 600.0)
            row.positionSaved(FilesItemId(id), 60.0)
        }

        assertEquals((12L downTo 3L).toList(), row.entries.value.map { it.fileId.value })
    }

    @Test
    fun reconcileDropsGoneAndFinishedFilesKeepsOtherFailuresAndRunsOnce() = runBlocking {
        listOf(Tears, Sintel, Gone, Flaky).forEach { id ->
            row.opened(id, "video-${id.value}.mkv", 600.0)
            row.positionSaved(id, 60.0)
        }
        var reads = 0
        val resolve: suspend (FilesItemId) -> PutioResult<FilesItem> = { id ->
            reads++
            when (id) {
                // Watched on another device: the position moves.
                Tears -> PutioResult.Success(video(Tears, "Tears renamed.mkv", startFrom = 300.0))
                // Finished on another device.
                Sintel -> PutioResult.Success(video(Sintel, "Sintel.mkv", startFrom = 0.0))
                Gone -> PutioResult.Failure(notFound())
                else -> PutioResult.Failure(PutioFailure.NetworkUnavailable(IllegalStateException("offline")))
            }
        }

        row.reconcile(resolve)
        row.reconcile(resolve)

        assertEquals(4, reads)
        assertEquals(listOf(Tears, Flaky), row.entries.value.map { it.fileId }.sortedBy { it.value })
        val tears = row.entries.value.single { it.fileId == Tears }
        assertEquals("Tears renamed.mkv", tears.name)
        assertEquals(300.0, tears.positionSeconds, 0.0)
    }

    @Test
    fun reconcileLeavesANewerSaveAloneAndReturnsARejectedSession() = runBlocking {
        row.opened(Tears, "Tears.of.Steel.mkv", 600.0)
        row.positionSaved(Tears, 60.0)
        // The player saves a newer position while the read is out; the read brings back the old one.
        val stale = row.reconcile { id ->
            row.positionSaved(id, 120.0)
            PutioResult.Success(video(Tears, "Tears.of.Steel.mkv", startFrom = 0.0))
        }
        assertEquals(null, stale)
        assertEquals(120.0, row.entries.value.single().positionSeconds, 0.0)

        val rejected = MobileContinueWatching(MemoryStore(row.entries.value))
            .reconcile { PutioResult.Failure(PutioFailure.AuthenticationRequired(apiException(401))) }
        assertTrue(rejected is PutioFailure.AuthenticationRequired)
    }

    @Test
    fun aClosedRowWritesNothing() {
        row.opened(Tears, "Tears.of.Steel.mkv", 600.0)
        row.close()
        row.positionSaved(Tears, 60.0)

        assertTrue(row.entries.value.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun startsFromWhatTheStoreKept() {
        val kept = ContinueWatchingEntry(Tears, "Tears.of.Steel.mkv", 60.0, 600.0, watchedAt = 5L)
        val restored = MobileContinueWatching(MemoryStore(listOf(kept)))

        assertEquals(listOf(kept), restored.entries.value)
        // A position for a kept entry updates it without the player resolving it again.
        restored.positionSaved(Tears, 120.0)
        assertEquals(120.0, restored.entries.value.single().positionSeconds, 0.0)
    }

    private class MemoryStore(initial: List<ContinueWatchingEntry> = emptyList()) : ContinueWatchingStore {
        var saved: List<ContinueWatchingEntry> = initial

        override fun read(): List<ContinueWatchingEntry> = saved

        override fun write(entries: List<ContinueWatchingEntry>) {
            saved = entries
        }
    }

    private fun video(id: FilesItemId, name: String, startFrom: Double) = FilesItem(
        id = id,
        parentId = FilesItemId(0L),
        name = name,
        type = PutioFileType.VIDEO,
        sizeBytes = 1L,
        createdAt = "2026-10-20T00:00:00",
        playback = FilesPlaybackProgress(startFrom, null),
    )

    private fun notFound(): PutioFailure =
        PutioFailure.ApiRejected(statusCode = 404, errorType = "NotFound", cause = apiException(404))

    private fun apiException(status: Int) = PutioApiException(
        request = PutioRequestData("GET", "https://api.put.io/v2/files/3"),
        resolvedStatusCode = status,
        httpStatusCode = status,
        resolvedErrorType = null,
        envelope = PutioApiErrorEnvelope(statusCode = status, errorType = null),
        responseBody = "{}",
        message = "Rejected",
    )

    private companion object {
        val Tears = FilesItemId(1L)
        val Sintel = FilesItemId(2L)
        val Gone = FilesItemId(3L)
        val Flaky = FilesItemId(4L)
    }
}
