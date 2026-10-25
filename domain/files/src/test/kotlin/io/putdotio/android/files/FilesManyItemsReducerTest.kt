package io.putdotio.android.files

import io.putdotio.android.PutioResult
import io.putdotio.android.toPutioFailure
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A selection deletes or moves its items in one request and checks each of them before the reload. */
class FilesManyItemsReducerTest {
    private val first = file(7L)
    private val second = file(8L)
    private val other = file(9L)
    private val ids = listOf(first.id, second.id)

    @Test
    fun deleteTakesOnlyListedDistinctRealItemsAndBlocksEachOfThem() {
        val root = loadedRoot()
        val invalid = listOf(
            listOf(first.id, FilesItemId(99L)), listOf(first.id, first.id), emptyList(),
            listOf(first.id, FilesItemId(0L)),
        )
        invalid.forEach { itemIds ->
            val event = FilesBrowserEvent.Delete(FilesFolder.Root.id, itemIds, FilesDeleteMode.TRASH)
            assertFalse(FilesBrowserReducer.reduce(root, event).consumed)
        }
        val deleting = FilesBrowserReducer.reduce(root, deleteBoth())
        assertEquals(ids, (deleting.effect as FilesBrowserEffect.Delete).itemIds)
        // Neither item opens while the delete settles.
        ids.forEach {
            assertFalse(FilesBrowserReducer.reduce(deleting.state, FilesBrowserEvent.OpenFolder(it)).consumed)
        }
        assertEquals(2, deleting.state.current.deleteOutcome?.itemCount)
    }

    @Test
    fun deleteIsConfirmedOnlyWhenEveryItemIsGoneAndCountsTheOnesLeft() {
        val checking = acknowledge(FilesBrowserReducer.reduce(loadedRoot(), deleteBoth()))
        assertEquals(ids, (checking.effect as FilesBrowserEffect.CheckDelete).itemIds)

        val gone = checkDelete(checking, listOf(notFound(), notFound())).state.current.deleteOutcome
        assertEquals(FilesDeleteStatus.NO_LONGER_AVAILABLE, gone?.status)
        assertEquals(0, gone?.remainingCount)

        val partly = checkDelete(checking, listOf(notFound(), PutioResult.Success(second))).state.current.deleteOutcome
        assertEquals(FilesDeleteStatus.STILL_PRESENT, partly?.status)
        assertEquals(1, partly?.remainingCount)

        // A check that answered for some other number of items proves nothing, so it can be retried.
        val mismatched = checkDelete(checking, listOf(notFound()))
        assertTrue(mismatched.state.current.operation is FilesFolderOperation.Failed)
    }

    @Test
    fun aConfirmedDeleteOfSeveralTurnsStillPresentWhenTheReloadListsOne() {
        val checking = acknowledge(FilesBrowserReducer.reduce(loadedRoot(), deleteBoth()))
        val reloading = checkDelete(checking, listOf(notFound(), notFound()))
        val reloaded = FilesBrowserReducer.reduce(reloading.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(reloading.effect).requestId, FilesPage(listOf(second, other), null),
        )).state.current.deleteOutcome
        assertEquals(FilesDeleteStatus.STILL_PRESENT, reloaded?.status)
        assertEquals(1, reloaded?.remainingCount)
    }

    @Test
    fun moveRefusesADestinationThatIsOneOfTheItemsOrTheirFolder() {
        val root = loadedRoot()
        listOf(first.id, second.id, FilesFolder.Root.id).forEach { destination ->
            assertFalse(FilesBrowserReducer.reduce(root, moveBoth(destination)).consumed)
        }
        val moving = FilesBrowserReducer.reduce(root, moveBoth(other.id))
        val effect = moving.effect as FilesBrowserEffect.Move
        assertEquals(ids, effect.itemIds)
        assertEquals(other.id, effect.destinationId)
    }

    @Test
    fun moveIsMovedOnlyWhenEveryItemIsInTheDestination() {
        val moving = FilesBrowserReducer.reduce(loadedRoot(), moveBoth(other.id))
        val checking = FilesBrowserReducer.reduce(moving.state, FilesBrowserEvent.MoveFinished(
            checkNotNull(moving.effect).requestId, PutioResult.Success(emptyList()),
        ))
        assertEquals(ids, (checking.effect as FilesBrowserEffect.CheckMove).itemIds)
        val moved = checkMove(checking, listOf(first, second).map { PutioResult.Success(it.copy(parentId = other.id)) })
        assertEquals(FilesMoveStatus.MOVED, moved.state.current.moveOutcome?.status)
        val partly = checkMove(checking, listOf(
            PutioResult.Success(first.copy(parentId = other.id)), PutioResult.Success(second),
        )).state.current.moveOutcome
        assertEquals(FilesMoveStatus.STILL_PRESENT, partly?.status)
        assertEquals(1, partly?.remainingCount)
    }

    @Test
    fun theControllerSendsOneRequestAndReadsEachItemBack() = runBlocking {
        val deletes = mutableListOf<List<FilesItemId>>()
        val reads = mutableListOf<FilesItemId>()
        val repository = object : StubFilesRepository() {
            var loads = 0
            override suspend fun loadFolder(folderId: FilesItemId): PutioResult<FilesPage> {
                loads += 1
                val items = if (loads == 1) listOf(first, second, other) else listOf(other)
                return PutioResult.Success(FilesPage(items, null))
            }
            override suspend fun delete(
                itemIds: List<FilesItemId>,
                mode: FilesDeleteMode,
            ): PutioResult<FileDeleteResult> {
                deletes += itemIds
                return PutioResult.Success(FileDeleteResult(status = "OK"))
            }
            override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> {
                synchronized(reads) { reads += itemId }
                return notFound()
            }
            override suspend fun move(itemIds: List<FilesItemId>, destinationId: FilesItemId):
                PutioResult<List<FileMoveError>> = error("Unexpected move")
        }
        val controller = FilesBrowserController(repository, this)
        try {
            withTimeout(5_000) { controller.state.first { it.current.content is FilesContent.Ready } }
            assertTrue(controller.dispatch(deleteBoth()))
            val settled = withTimeout(5_000) {
                controller.state.first { it.current.deleteOutcome?.status == FilesDeleteStatus.NO_LONGER_AVAILABLE &&
                    it.current.operation == FilesFolderOperation.Idle }
            }
            assertEquals(listOf(ids), deletes)
            assertEquals(ids.toSet(), reads.toSet())
            assertEquals(listOf(other), settled.current.content.items())
        } finally {
            controller.close()
        }
    }

    private fun deleteBoth() = FilesBrowserEvent.Delete(FilesFolder.Root.id, ids, FilesDeleteMode.TRASH)

    private fun moveBoth(destination: FilesItemId) = FilesBrowserEvent.Move(FilesFolder.Root.id, ids, destination)

    private fun acknowledge(deleting: FilesBrowserTransition) =
        FilesBrowserReducer.reduce(deleting.state, FilesBrowserEvent.DeleteFinished(
            checkNotNull(deleting.effect).requestId, PutioResult.Success(FileDeleteResult(status = "OK")),
        ))

    private fun checkDelete(checking: FilesBrowserTransition, results: List<PutioResult<FilesItem>>) =
        FilesBrowserReducer.reduce(
            checking.state, FilesBrowserEvent.DeleteChecked(checkNotNull(checking.effect).requestId, results),
        )

    private fun checkMove(checking: FilesBrowserTransition, results: List<PutioResult<FilesItem>>) =
        FilesBrowserReducer.reduce(
            checking.state, FilesBrowserEvent.MoveChecked(checkNotNull(checking.effect).requestId, results),
        )

    private fun loadedRoot(): FilesBrowserState {
        val started = FilesBrowserReducer.start()
        return FilesBrowserReducer.reduce(started.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(started.effect).requestId, FilesPage(listOf(first, second, other, file(0L)), null),
        )).state
    }

    private fun file(id: Long) =
        FilesItem(FilesItemId(id), FilesItemId(0L), "folder-$id", PutioFileType.FOLDER, 1L, "2026-09-06")

    private fun notFound(): PutioResult<FilesItem> = PutioResult.Failure(PutioApiException(
        request = PutioRequestData("GET", "https://api.put.io/v2/files/7"),
        resolvedStatusCode = 404, httpStatusCode = 404, resolvedErrorType = null,
        envelope = PutioApiErrorEnvelope(statusCode = 404), responseBody = "{}", message = "Not found",
    ).toPutioFailure())
}
