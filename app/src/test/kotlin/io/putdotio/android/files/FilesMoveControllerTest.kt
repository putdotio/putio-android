package io.putdotio.android.files

import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesMoveControllerTest {
    @Test
    fun uncertainMoveCancelsPagingAndRetriesOnlyReadsWithoutAnotherPost() = runBlocking {
        val item = FilesItem(FilesItemId(7L), FilesFolder.Root.id, "file", PutioFileType.VIDEO, 1L, "2026-09-06")
        val destinationId = FilesItemId(9L)
        val pagingStarted = CompletableDeferred<Unit>()
        val pagingCancelled = CompletableDeferred<Unit>()
        val repository = object : StubFilesRepository() {
            var moves = 0
            var loads = 0
            val reads = mutableListOf<FilesItemId>()
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> {
                loads += 1
                return when (loads) {
                    1 -> FilesRepositoryResult.Success(FilesPage(listOf(item), FilesCursor("next")))
                    2 -> FilesRepositoryResult.Failure(failure("reload offline"))
                    else -> FilesRepositoryResult.Success(FilesPage(emptyList(), null))
                }
            }
            override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> {
                pagingStarted.complete(Unit)
                try { awaitCancellation() } finally { pagingCancelled.complete(Unit) }
            }
            override suspend fun move(
                itemId: FilesItemId,
                destinationId: FilesItemId,
            ): FilesRepositoryResult<List<FileMoveError>> {
                moves += 1
                error("response lost after submission")
            }
            override suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem> {
                reads += itemId
                return if (reads.size == 1) FilesRepositoryResult.Failure(failure("read offline")) else
                    FilesRepositoryResult.Success(item.copy(parentId = destinationId))
            }
        }
        val controller = FilesBrowserController(repository, this)
        try {
            withTimeout(5_000) { controller.state.first { it.current.content is FilesContent.Ready } }
            controller.dispatch(FilesBrowserEvent.LoadNextPage)
            withTimeout(5_000) { pagingStarted.await() }
            val move = FilesBrowserEvent.Move(FilesFolder.Root.id, item.id, destinationId)
            assertTrue(controller.dispatch(move))
            assertFalse(controller.dispatch(move))
            withTimeout(5_000) { pagingCancelled.await() }
            withTimeout(5_000) { controller.state.first { it.current.operation is FilesFolderOperation.Failed } }
            assertEquals(FilesMoveStatus.UNKNOWN, controller.state.value.current.moveOutcome?.status)
            assertTrue(controller.dispatch(FilesBrowserEvent.Retry))
            withTimeout(5_000) { controller.state.first {
                val operation = it.current.operation as? FilesFolderOperation.Failed
                operation?.phase == FilesFolderOperationPhase.RELOADING
            } }
            assertTrue(controller.dispatch(FilesBrowserEvent.Retry))
            withTimeout(5_000) { controller.state.first { it.current.operation == FilesFolderOperation.Idle } }
            assertEquals(1, repository.moves)
            assertEquals(listOf(item.id, item.id), repository.reads)
            assertEquals(3, repository.loads)
            assertEquals(FilesMoveStatus.MOVED, controller.state.value.current.moveOutcome?.status)
            assertTrue(controller.state.value.current.moveOutcome?.failure is FilesFailure.Unexpected)
        } finally {
            controller.close()
        }
    }

    private fun failure(message: String) = FilesFailure.Unexpected(IllegalStateException(message))
}
