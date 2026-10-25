package io.putdotio.android.files

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.trash.TrashBulkSelection
import io.putdotio.android.trash.TrashPage
import io.putdotio.android.trash.TrashRepository
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileFilesActionsControllerTest {
    private val video = FilesItem(
        FilesItemId(7L), FilesItemId(0L), "Blue.Lights.S04E01.mkv", PutioFileType.VIDEO, 1L, "2026-10-13",
        playback = FilesPlaybackProgress(startFromSeconds = 600.0, durationSeconds = 3_000.0),
    )
    private val events = mutableListOf<FilesBrowserEvent>()
    private val watched = RecordingWatched()
    private val trash = RecordingTrash()
    private val opened = mutableListOf<Pair<String, String>>()
    private var vlcInstalled = true
    private var rejected = 0
    private var stream: FilesStreamUrlResult = FilesStreamUrlResult.Ready(STREAM)
    private var streamGate: CompletableDeferred<Unit>? = null
    private val row = MobileContinueWatching(MemoryStore(listOf(entry())))

    @Test
    fun markingAVideoWatchedSavesItsEndAndTakesItOutOfContinueWatching() = runTest {
        val controller = controller()
        controller.setWatched(video, watched = true)
        advanceUntilIdle()
        assertEquals(listOf(video.id to 3_000.0), watched.positions)
        assertEquals(listOf(FilesBrowserEvent.PlaybackPositionReported(video.id, 3_000.0)), events)
        assertTrue(row.entries.value.isEmpty())
    }

    @Test
    fun markingAVideoUnwatchedDropsItsPosition() = runTest {
        val controller = controller()
        controller.setWatched(video, watched = false)
        advanceUntilIdle()
        assertEquals(listOf(video.id), watched.cleared)
        assertEquals(listOf(FilesBrowserEvent.PlaybackPositionReported(video.id, 0.0)), events)
        assertTrue(row.entries.value.isEmpty())
    }

    @Test
    fun aFailedWatchedWriteKeepsTheRowAndExplainsWhileA401RejectsTheSession() = runTest {
        val controller = controller()
        val notices = mutableListOf<MobileFilesNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.notices.toList(notices) }
        watched.result = PutioResult.Failure(PutioFailure.NetworkUnavailable(IllegalStateException("offline")))
        controller.setWatched(video, watched = true)
        advanceUntilIdle()
        assertEquals(listOf(R.string.mobile_files_mark_watched_failed), notices.map { it.message })
        assertEquals(1, row.entries.value.size)

        val rejection = PutioFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        watched.result = PutioResult.Failure(rejection)
        controller.setWatched(video, watched = true)
        advanceUntilIdle()
        assertEquals(1, rejected)
        assertEquals(1, notices.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun aNewerWatchedChoiceWaitsForTheOneSentAndBothSettleInOrder() = runTest {
        val controller = controller()
        val first = CompletableDeferred<PutioResult<Unit>>()
        watched.next = { first.await() }
        controller.setWatched(video, watched = true)
        runCurrent()
        // A newer choice for the same file waits for the one already sent.
        watched.next = { PutioResult.Success(Unit) }
        controller.setWatched(video, watched = false)
        advanceUntilIdle()
        assertEquals(listOf("set"), watched.answered)
        first.complete(PutioResult.Success(Unit))
        advanceUntilIdle()
        // put.io applies them in order, and the row follows each in that order.
        assertEquals(listOf("set", "clear"), watched.answered)
        assertEquals(
            listOf(
                FilesBrowserEvent.PlaybackPositionReported(video.id, 3_000.0),
                FilesBrowserEvent.PlaybackPositionReported(video.id, 0.0),
            ),
            events,
        )
    }

    @Test
    fun aWriteThatReturnsAfterTheSessionClosedSettlesNothing() = runTest {
        // Its own job on the test's dispatcher, so closing it leaves the test running.
        val controller = controller(CoroutineScope(coroutineContext.minusKey(Job)))
        val answer = CompletableDeferred<PutioResult<Unit>>()
        watched.next = { answer.await() }
        controller.setWatched(video, watched = true)
        runCurrent()
        controller.close()
        answer.complete(PutioResult.Failure(PutioFailure.AuthenticationRequired(PutioConfigurationException("late"))))
        advanceUntilIdle()
        assertEquals(0, rejected)
        assertTrue(events.isEmpty())
    }

    @Test
    fun openInVlcHandsTheDownloadTokenStreamToVlcAndExplainsWhatStopsIt() = runTest {
        val controller = controller()
        val notices = mutableListOf<MobileFilesNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.notices.toList(notices) }
        controller.openInVlc(video.id, video.name)
        advanceUntilIdle()
        assertEquals(listOf((stream as FilesStreamUrlResult.Ready).url to video.name), opened)

        vlcInstalled = false
        controller.openInVlc(video.id, video.name)
        advanceUntilIdle()
        stream = FilesStreamUrlResult.DownloadTokenUnavailable
        controller.openInVlc(video.id, video.name)
        advanceUntilIdle()
        assertEquals(
            listOf(R.string.mobile_files_vlc_missing, R.string.mobile_files_vlc_unavailable),
            notices.map { it.message },
        )
    }

    @Test
    fun tapsDuringTheTokenLookupOpenVlcOnce() = runTest {
        val controller = controller()
        val lookup = CompletableDeferred<Unit>()
        streamGate = lookup
        controller.openInVlc(video.id, video.name)
        runCurrent()
        controller.openInVlc(video.id, video.name)
        lookup.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, opened.size)
        // Once the handoff is done, the next tap opens VLC again.
        controller.openInVlc(video.id, video.name)
        advanceUntilIdle()
        assertEquals(2, opened.size)
    }

    @Test
    fun vlcGetsTheStreamByPackageAndNothingElse() {
        val url = STREAM
        val intent = vlcIntent(url, "Blue.Lights.S04E01.mkv")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(VLC_PACKAGE, intent.`package`)
        assertEquals(url, intent.dataString)
        assertEquals("video/*", intent.type)
        assertEquals("Blue.Lights.S04E01.mkv", intent.getStringExtra("title"))
        assertEquals(setOf("title"), intent.extras?.keySet())
    }

    @Test
    fun undoRestoresTheTrashMoveAndReadsTheFolderAgain() = runTest {
        val controller = controller()
        val notices = mutableListOf<MobileFilesNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.notices.toList(notices) }
        val outcome = FilesDeleteOutcome(
            FilesRequestId(4L),
            FilesFolderOperationIntent.Delete(video.id, FilesDeleteMode.TRASH),
            video.name,
            status = FilesDeleteStatus.NO_LONGER_AVAILABLE,
        )
        controller.undoTrash(outcome)
        advanceUntilIdle()
        assertEquals(listOf(video.id), trash.restored)
        assertEquals(listOf(FilesBrowserEvent.TrashUndone(outcome), FilesBrowserEvent.ReloadIfStale), events)

        // Permanent deletion has nothing to restore.
        controller.undoTrash(outcome.copy(intent = outcome.intent.copy(mode = FilesDeleteMode.PERMANENT)))
        trash.result = PutioResult.Failure(PutioFailure.NetworkUnavailable(IllegalStateException("offline")))
        controller.undoTrash(outcome)
        advanceUntilIdle()
        assertEquals(2, trash.restored.size)
        assertEquals(2, events.size)
        assertEquals(listOf(R.string.mobile_files_undo_failed), notices.map { it.message })
    }

    private fun TestScope.controller(scope: CoroutineScope = this) = MobileFilesActionsController(
        scope = scope,
        sources = MobileFilesActionSources(watched, { streamGate?.await(); stream }, trash),
        onFilesEvent = { events += it; true },
        onPositionSaved = row::positionSaved,
        onAuthenticationRequired = { rejected++ },
        handoff = { url, name -> vlcInstalled.also { if (it) opened += url to name } },
    )

    private fun entry() = ContinueWatchingEntry(video.id, video.name, 600.0, 3_000.0, watchedAt = 1L)

    private class RecordingWatched : FilesWatchedRepository {
        val positions = mutableListOf<Pair<FilesItemId, Double>>()
        val cleared = mutableListOf<FilesItemId>()
        var result: PutioResult<Unit> = PutioResult.Success(Unit)
        var next: (suspend () -> PutioResult<Unit>)? = null

        /** Calls in the order they were sent. */
        val answered = mutableListOf<String>()

        override suspend fun setPosition(itemId: FilesItemId, seconds: Double): PutioResult<Unit> {
            positions += itemId to seconds
            answered += "set"
            // Like a blocking network call, a scripted answer returns even after cancellation.
            return next?.let { answer -> withContext(NonCancellable) { answer() } } ?: result
        }

        override suspend fun clearPosition(itemId: FilesItemId): PutioResult<Unit> {
            cleared += itemId
            answered += "clear"
            return next?.let { answer -> withContext(NonCancellable) { answer() } } ?: result
        }
    }

    private class RecordingTrash : TrashRepository {
        val restored = mutableListOf<FilesItemId>()
        var result: PutioResult<Unit> = PutioResult.Success(Unit)

        override suspend fun restore(itemId: FilesItemId): PutioResult<Unit> = result.also { restored += itemId }
        override suspend fun load(): PutioResult<TrashPage> = error("unused")
        override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<TrashPage> = error("unused")
        override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> = error("unused")
        override suspend fun deleteItem(itemId: FilesItemId): PutioResult<Unit> = error("unused")
        override suspend fun restoreAll(selection: TrashBulkSelection): PutioResult<Unit> = error("unused")
        override suspend fun empty(): PutioResult<Unit> = error("unused")
    }

    private class MemoryStore(private var entries: List<ContinueWatchingEntry>) : ContinueWatchingStore {
        override fun read(): List<ContinueWatchingEntry> = entries

        override fun write(entries: List<ContinueWatchingEntry>) {
            this.entries = entries
        }
    }

    private companion object {
        const val STREAM = "https://api.put.io/v2/files/7/stream?oauth_token=download-token"
    }
}
