package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesMoveDestinationControllerTest {
    private val source = folder(7L)
    private val target = folder(8L)

    @Test
    fun rootNavigationEmptyPagingRetryAndCycleGuardsKeepTheirContracts() = runBlocking {
        val failure = FilesFailure.Unexpected(IllegalStateException("offline page"))
        val calls = mutableListOf<Pair<FilesItemId, FilesCursor?>>()
        var continuations = 0
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Source browser must not load")
            override suspend fun loadMoveDestinations(
                folderId: FilesItemId,
                cursor: FilesCursor?,
            ): FilesRepositoryResult<FilesPage> {
                calls += folderId to cursor
                return when {
                    folderId == FilesFolder.Root.id -> FilesRepositoryResult.Success(
                        FilesPage(listOf(source, target, folder(-1L)), null),
                    )
                    cursor == null -> FilesRepositoryResult.Success(FilesPage(emptyList(), FilesCursor("next")))
                    ++continuations == 1 -> FilesRepositoryResult.Failure(failure)
                    else -> FilesRepositoryResult.Success(FilesPage(listOf(source, target, folder(10L)), cursor))
                }
            }
        }
        val controller = FilesMoveDestinationController(source, FilesFolder.Root.id, repository, this)
        try {
            assertFalse(controller.state.value.canMoveHere)
            controller.awaitState { it.current.content is FilesContent.Ready }
            assertFalse(controller.state.value.canMoveHere)
            assertFalse(controller.dispatch(FilesMoveDestinationEvent.OpenFolder(source.id)))
            assertFalse(controller.dispatch(FilesMoveDestinationEvent.OpenFolder(FilesItemId(-1L))))
            assertTrue(controller.dispatch(FilesMoveDestinationEvent.OpenFolder(target.id)))
            controller.awaitState { it.current.content is FilesContent.Empty }
            assertTrue(controller.state.value.canMoveHere)
            assertTrue(controller.dispatch(FilesMoveDestinationEvent.LoadNextPage))
            val failed = controller.awaitState { it.current.content.paging() is FilesPaging.Failed }
            assertSame(failure, (failed.current.content.paging() as FilesPaging.Failed).failure)
            assertTrue(controller.dispatch(FilesMoveDestinationEvent.Retry))
            val complete = controller.awaitState { it.current.content.paging() == FilesPaging.Complete }
            assertEquals(listOf(FilesFolder.Root.id, target.id), complete.path.map { it.id })
            assertFalse(complete.canOpenFolder(source.id))
            assertFalse(complete.canOpenFolder(target.id))
            assertTrue(complete.canOpenFolder(FilesItemId(10L)))
            assertFalse(controller.dispatch(FilesMoveDestinationEvent.LoadNextPage))
            assertTrue(controller.dispatch(FilesMoveDestinationEvent.NavigateBack))
            assertFalse(controller.state.value.canNavigateBack)
            assertEquals(4, calls.size)
        } finally {
            controller.close()
        }
    }

    @Test
    fun rootCanBeChosenFromNestedSourceAndInitialLoadFailureCanRetry() = runBlocking {
        var loads = 0
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Unexpected source read")
            override suspend fun loadMoveDestinations(
                folderId: FilesItemId,
                cursor: FilesCursor?,
            ): FilesRepositoryResult<FilesPage> {
                loads += 1
                return if (loads == 1) {
                    FilesRepositoryResult.Failure(FilesFailure.Unexpected(IllegalStateException("offline")))
                } else {
                    FilesRepositoryResult.Success(FilesPage(emptyList(), null))
                }
            }
        }
        val controller = FilesMoveDestinationController(source.copy(parentId = target.id), target.id, repository, this)
        try {
            controller.awaitState { it.current.content is FilesContent.Failed }
            assertFalse(controller.state.value.canMoveHere)
            assertTrue(controller.dispatch(FilesMoveDestinationEvent.Retry))
            controller.awaitState { it.current.content is FilesContent.Empty }
            assertTrue(controller.state.value.canMoveHere)
            assertEquals(2, loads)
        } finally {
            controller.close()
        }
    }

    @Test
    fun backAndCloseRejectLateReadCompletionsAndNeverStartMutations() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val childPage = CompletableDeferred<FilesRepositoryResult<FilesPage>>()
        val finished = CompletableDeferred<Unit>()
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Unexpected source read")
            override suspend fun loadMoveDestinations(
                folderId: FilesItemId,
                cursor: FilesCursor?,
            ): FilesRepositoryResult<FilesPage> =
                if (folderId == FilesFolder.Root.id) {
                    FilesRepositoryResult.Success(FilesPage(listOf(target), null))
                } else {
                    started.complete(Unit)
                    withContext(NonCancellable) {
                        try { childPage.await() } finally { finished.complete(Unit) }
                    }
                }
        }
        val controller = FilesMoveDestinationController(source, FilesFolder.Root.id, repository, this)
        try {
            controller.awaitState { it.current.content is FilesContent.Ready }
            controller.dispatch(FilesMoveDestinationEvent.OpenFolder(target.id))
            withTimeout(5_000) { started.await() }
            controller.dispatch(FilesMoveDestinationEvent.NavigateBack)
            val root = controller.state.value
            childPage.complete(FilesRepositoryResult.Success(FilesPage(listOf(folder(99L)), null)))
            withTimeout(5_000) { finished.await() }
            assertEquals(root, controller.state.value)
            controller.close()
            assertFalse(controller.dispatch(FilesMoveDestinationEvent.OpenFolder(target.id)))
        } finally {
            controller.close()
            childPage.complete(FilesRepositoryResult.Success(FilesPage(emptyList(), null)))
        }
    }

    private suspend fun FilesMoveDestinationController.awaitState(predicate: (FilesMoveDestinationState) -> Boolean) =
        withTimeout(5_000) { state.first(predicate) }
    private fun folder(id: Long) = FilesItem(
        FilesItemId(id), FilesFolder.Root.id, "folder-$id", PutioFileType.FOLDER, 1L, "2026-09-06",
    )
}
