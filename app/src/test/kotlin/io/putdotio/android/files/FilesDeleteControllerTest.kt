package io.putdotio.android.files

import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesDeleteControllerTest {
    @Test
    fun uncertainDeleteRetriesItemAndFolderReadsWithoutAnotherPost() = runBlocking {
        for (throwFromDelete in listOf(false, true)) {
            val repository = DeleteRepository(throwFromDelete = throwFromDelete)
            val controller = FilesBrowserController(repository, this)
            try {
                withTimeout(5_000) { controller.state.first { it.current.content is FilesContent.Ready } }
                assertTrue(controller.dispatch(deleteEvent))
                withTimeout(5_000) { controller.state.first { it.current.operation is FilesFolderOperation.Failed } }
                assertEquals(FilesDeleteStatus.UNKNOWN, controller.state.value.current.deleteOutcome?.status)
                assertFalse(controller.dispatch(deleteEvent))
                controller.dispatch(FilesBrowserEvent.Retry)
                withTimeout(5_000) {
                    controller.state.first {
                        val operation = it.current.operation as? FilesFolderOperation.Failed
                        operation?.phase == FilesFolderOperationPhase.RELOADING
                    }
                }
                controller.dispatch(FilesBrowserEvent.Retry)
                withTimeout(5_000) { controller.state.first { it.current.operation == FilesFolderOperation.Idle } }
                assertEquals(1, repository.deletes)
                assertEquals(listOf(item.id, item.id), repository.reads)
                assertEquals(3, repository.folderLoads)
                assertEquals(FilesDeleteStatus.STILL_PRESENT, controller.state.value.current.deleteOutcome?.status)
                if (throwFromDelete) {
                    assertTrue(controller.state.value.current.deleteOutcome?.failure is FilesFailure.Unexpected)
                }
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun deletionCancelsPagingAndIgnoresDuplicateConfirmWhilePostIsPending() = runBlocking {
        val pagingStarted = CompletableDeferred<Unit>()
        val pagingCancelled = CompletableDeferred<Unit>()
        val deleteResult = CompletableDeferred<FilesRepositoryResult<FileDeleteResult>>()
        val repository = object : FilesRepository {
            var deletes = 0
            override suspend fun loadFolder(folderId: FilesItemId) =
                FilesRepositoryResult.Success(FilesPage(listOf(item), FilesCursor("next")))
            override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> {
                pagingStarted.complete(Unit)
                try { awaitCancellation() } finally { pagingCancelled.complete(Unit) }
            }
            override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort): FilesRepositoryResult<Unit> =
                error("Unexpected sort")
            override suspend fun rename(itemId: FilesItemId, name: String): FilesRepositoryResult<Unit> =
                error("Unexpected rename")
            override suspend fun resolveItem(itemId: FilesItemId) = FilesRepositoryResult.Success(item)
            override suspend fun delete(
                itemId: FilesItemId,
                mode: FilesDeleteMode,
            ): FilesRepositoryResult<FileDeleteResult> {
                deletes += 1
                return deleteResult.await()
            }
        }
        val controller = FilesBrowserController(repository, this)
        try {
            withTimeout(5_000) { controller.state.first { it.current.content is FilesContent.Ready } }
            controller.dispatch(FilesBrowserEvent.LoadNextPage)
            withTimeout(5_000) { pagingStarted.await() }
            assertTrue(controller.dispatch(deleteEvent))
            assertFalse(controller.dispatch(deleteEvent))
            withTimeout(5_000) { pagingCancelled.await() }
            deleteResult.complete(FilesRepositoryResult.Success(FileDeleteResult(status = "OK")))
            withTimeout(5_000) { controller.state.first { it.current.operation == FilesFolderOperation.Idle } }
            assertEquals(1, repository.deletes)
            assertEquals(FilesDeleteStatus.STILL_PRESENT, controller.state.value.current.deleteOutcome?.status)
        } finally {
            controller.close()
        }
    }

    @Test
    fun parentDeleteReconcilesWhileDistinctChildLoadRemainsPending() = runBlocking {
        val repository = DescendingDeleteRepository()
        val controller = FilesBrowserController(repository, this)
        try {
            withTimeout(5_000) { controller.state.first { it.current.content is FilesContent.Ready } }
            assertTrue(controller.dispatch(deleteEvent))
            withTimeout(5_000) { repository.postStarted.await() }
            assertTrue(controller.dispatch(FilesBrowserEvent.OpenFolder(repository.child.id)))
            withTimeout(5_000) { repository.childStarted.await() }
            val childLoading = controller.state.value.current.content as FilesContent.Loading
            val acknowledgement = FileDeleteResult(status = "OK")
            repository.postResult.complete(FilesRepositoryResult.Success(acknowledgement))
            withTimeout(5_000) { repository.reloadStarted.await() }
            assertSame(childLoading, controller.state.value.current.content)
            assertEquals(FilesFolderOperationPhase.RELOADING,
                (controller.state.value.stack.first().operation as FilesFolderOperation.Loading).phase)
            repository.parentReload.complete(FilesRepositoryResult.Success(FilesPage(listOf(repository.child), null)))
            val reconciled = withTimeout(5_000) {
                controller.state.first { it.stack.first().operation == FilesFolderOperation.Idle }
            }
            val parent = reconciled.stack.first()
            assertEquals(repository.child.id, reconciled.current.folder.id)
            assertSame(childLoading, reconciled.current.content)
            assertEquals(FilesDeleteStatus.NO_LONGER_AVAILABLE, parent.deleteOutcome?.status)
            assertSame(acknowledgement, parent.deleteOutcome?.response)
            assertEquals(listOf(repository.child), (parent.content as FilesContent.Ready).items)
            assertEquals(listOf(item.id), repository.posts)
            assertEquals(listOf(item.id), repository.exactReads)
            assertEquals(listOf(FilesFolder.Root.id, repository.child.id, FilesFolder.Root.id), repository.folderReads)
            repository.childResult.complete(
                FilesRepositoryResult.Success(FilesPage(listOf(repository.grandchild), null)),
            )
            withTimeout(5_000) { controller.state.first { it.current.content is FilesContent.Ready } }
            assertEquals(listOf(repository.grandchild),
                (controller.state.value.current.content as FilesContent.Ready).items)
            assertTrue(controller.dispatch(FilesBrowserEvent.NavigateBack))
            assertSame(parent, controller.state.value.current)
            assertEquals(FilesFolder.Root.id, controller.state.value.current.folder.id)
            assertEquals(1, repository.posts.size)
        } finally {
            controller.close()
        }
    }

    private class DescendingDeleteRepository : FilesRepository {
        val child = item.copy(id = FilesItemId(8L), name = "distinct child")
        val grandchild = item.copy(id = FilesItemId(9L), parentId = child.id, name = "grandchild")
        val postStarted = CompletableDeferred<Unit>()
        val childStarted = CompletableDeferred<Unit>()
        val reloadStarted = CompletableDeferred<Unit>()
        val postResult = CompletableDeferred<FilesRepositoryResult<FileDeleteResult>>()
        val childResult = CompletableDeferred<FilesRepositoryResult<FilesPage>>()
        val parentReload = CompletableDeferred<FilesRepositoryResult<FilesPage>>()
        val posts = mutableListOf<FilesItemId>()
        val exactReads = mutableListOf<FilesItemId>()
        val folderReads = mutableListOf<FilesItemId>()
        override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> {
            folderReads += folderId
            return when {
                folderId == child.id -> {
                    childStarted.complete(Unit)
                    childResult.await()
                }
                folderId == FilesFolder.Root.id && folderReads.size == 1 ->
                    FilesRepositoryResult.Success(FilesPage(listOf(item, child), null))
                folderId == FilesFolder.Root.id -> {
                    reloadStarted.complete(Unit)
                    parentReload.await()
                }
                else -> error("Unexpected folder read")
            }
        }
        override suspend fun delete(
            itemId: FilesItemId,
            mode: FilesDeleteMode,
        ): FilesRepositoryResult<FileDeleteResult> {
            assertEquals(item.id, itemId)
            assertEquals(FilesDeleteMode.TRASH, mode)
            posts += itemId
            postStarted.complete(Unit)
            return postResult.await()
        }
        override suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem> {
            exactReads += itemId
            assertEquals(item.id, itemId)
            return FilesRepositoryResult.Failure(PutioApiException(
                request = PutioRequestData("GET", "https://api.put.io/v2/files/7"),
                resolvedStatusCode = 404, httpStatusCode = 404, resolvedErrorType = null,
                envelope = PutioApiErrorEnvelope(statusCode = 404), responseBody = "{}", message = "Not found",
            ).toFilesFailure())
        }
        override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> =
            error("Unexpected page")
        override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort): FilesRepositoryResult<Unit> =
            error("Unexpected sort")
        override suspend fun rename(itemId: FilesItemId, name: String): FilesRepositoryResult<Unit> =
            error("Unexpected rename")
    }

    private class DeleteRepository(private val throwFromDelete: Boolean) : FilesRepository {
        var deletes = 0
        var folderLoads = 0
        val reads = mutableListOf<FilesItemId>()
        override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> {
            folderLoads += 1
            return if (folderLoads == 2) {
                failure("reload offline")
            } else {
                FilesRepositoryResult.Success(FilesPage(listOf(item), null))
            }
        }
        override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> =
            error("Unexpected page")
        override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort): FilesRepositoryResult<Unit> =
            error("Unexpected sort")
        override suspend fun rename(itemId: FilesItemId, name: String): FilesRepositoryResult<Unit> =
            error("Unexpected rename")
        override suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem> {
            reads += itemId
            return if (reads.size == 1) failure("read offline") else FilesRepositoryResult.Success(item)
        }
        override suspend fun delete(
            itemId: FilesItemId,
            mode: FilesDeleteMode,
        ): FilesRepositoryResult<FileDeleteResult> {
            deletes += 1
            if (throwFromDelete) error("connection lost after submission")
            return FilesRepositoryResult.Success(FileDeleteResult(status = "OK"))
        }
    }

    companion object {
        private val item = FilesItem(FilesItemId(7L), FilesItemId(0L), "folder", PutioFileType.FOLDER, 1L, "2026-09-06")
        private val deleteEvent = FilesBrowserEvent.Delete(FilesFolder.Root.id, item.id, FilesDeleteMode.TRASH)
        private fun failure(message: String) =
            FilesRepositoryResult.Failure(FilesFailure.Unexpected(IllegalStateException(message)))
    }
}
