package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesBrowserControllerTest {

    @Test
    fun navigateBackPreservesParentPagingRequest() =
        runBlocking {
            val pagingResult = CompletableDeferred<FilesRepositoryResult<FilesPage>>()
            val folder = item(7L, "Shows", PutioFileType.FOLDER)
            val repository =
                object : FilesRepository {
                    override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                        if (folderId == FilesFolder.Root.id) {
                            FilesRepositoryResult.Success(FilesPage(listOf(folder), FilesCursor("next")))
                        } else {
                            FilesRepositoryResult.Success(
                                FilesPage(listOf(item(8L, "episode.mkv", PutioFileType.VIDEO)), null),
                            )
                        }

                    override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> =
                        pagingResult.await()

                    override suspend fun persistSort(
                        folderId: FilesItemId,
                        sort: FilesSort,
                    ): FilesRepositoryResult<Unit> = FilesRepositoryResult.Success(Unit)
                }
            val controller = FilesBrowserController(repository, this)

            try {
                controller.awaitState { it.current.content is FilesContent.Ready }
                assertTrue(controller.dispatch(FilesBrowserEvent.LoadNextPage))
                controller.awaitState {
                    val content = it.current.content as? FilesContent.Ready
                    content?.paging is FilesPaging.Loading
                }

                assertTrue(controller.dispatch(FilesBrowserEvent.OpenFolder(folder.id)))
                controller.awaitState { it.stack.size == 2 && it.current.content is FilesContent.Ready }

                assertTrue(controller.dispatch(FilesBrowserEvent.NavigateBack))
                val restoredLoading = controller.state.value.current.content as FilesContent.Ready
                assertTrue(restoredLoading.paging is FilesPaging.Loading)

                pagingResult.complete(
                    FilesRepositoryResult.Success(
                        FilesPage(listOf(item(9L, "movie.mkv", PutioFileType.VIDEO)), null),
                    ),
                )
                controller.awaitState {
                    val parent = it.stack.first().content as? FilesContent.Ready
                    parent?.paging == FilesPaging.Complete && parent.items.size == 2
                }

                val restored = controller.state.value.current.content as FilesContent.Ready
                assertEquals(listOf(7L, 9L), restored.items.map { it.id.value })
            } finally {
                controller.close()
            }
        }

    @Test
    fun navigateBackCancelsTheRemovedChildRequest() =
        runBlocking {
            val childStarted = CompletableDeferred<Unit>()
            val childCancelled = CompletableDeferred<Unit>()
            val folder = item(7L, "Shows", PutioFileType.FOLDER)
            val repository =
                object : FilesRepository {
                    override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                        if (folderId == FilesFolder.Root.id) {
                            FilesRepositoryResult.Success(FilesPage(listOf(folder), null))
                        } else {
                            childStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                childCancelled.complete(Unit)
                            }
                        }

                    override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> =
                        error("No continuation expected")

                    override suspend fun persistSort(
                        folderId: FilesItemId,
                        sort: FilesSort,
                    ): FilesRepositoryResult<Unit> = FilesRepositoryResult.Success(Unit)
                }
            val controller = FilesBrowserController(repository, this)

            try {
                controller.awaitState { it.current.content is FilesContent.Ready }
                assertTrue(controller.dispatch(FilesBrowserEvent.OpenFolder(folder.id)))
                withTimeout(TEST_TIMEOUT_MILLIS) { childStarted.await() }
                controller.awaitState { it.stack.size == 2 && it.current.content is FilesContent.Loading }

                assertTrue(controller.dispatch(FilesBrowserEvent.NavigateBack))

                withTimeout(TEST_TIMEOUT_MILLIS) { childCancelled.await() }
                assertEquals(listOf(FilesFolder.Root), controller.state.value.path)
            } finally {
                controller.close()
            }
        }

    @Test
    fun reportsUnhandledRootBackAndDoesNotCancelItsParentScope() =
        runBlocking {
            val repository =
                object : FilesRepository {
                    override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                        FilesRepositoryResult.Success(FilesPage(emptyList(), null))

                    override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> =
                        error("No continuation expected")

                    override suspend fun persistSort(
                        folderId: FilesItemId,
                        sort: FilesSort,
                    ): FilesRepositoryResult<Unit> = FilesRepositoryResult.Success(Unit)
                }
            val controller = FilesBrowserController(repository, this)

            controller.awaitState { it.current.content is FilesContent.Empty }
            assertFalse(controller.dispatch(FilesBrowserEvent.NavigateBack))
            controller.close()

            assertTrue(coroutineContext[Job]?.isActive == true)
        }

    @Test
    fun refreshCancelsTheSupersededPagingRequest() =
        runBlocking {
            val pagingStarted = CompletableDeferred<Unit>()
            val pagingCancelled = CompletableDeferred<Unit>()
            var folderLoads = 0
            val repository =
                object : FilesRepository {
                    override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> {
                        folderLoads += 1
                        return FilesRepositoryResult.Success(
                            if (folderLoads == 1) {
                                FilesPage(listOf(item(1L, "old.mkv", PutioFileType.VIDEO)), FilesCursor("next"))
                            } else {
                                FilesPage(listOf(item(2L, "fresh.mkv", PutioFileType.VIDEO)), null)
                            },
                        )
                    }

                    override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> {
                        pagingStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            pagingCancelled.complete(Unit)
                        }
                    }

                    override suspend fun persistSort(
                        folderId: FilesItemId,
                        sort: FilesSort,
                    ): FilesRepositoryResult<Unit> = FilesRepositoryResult.Success(Unit)
                }
            val controller = FilesBrowserController(repository, this)

            try {
                controller.awaitState { it.current.content is FilesContent.Ready }
                assertTrue(controller.dispatch(FilesBrowserEvent.LoadNextPage))
                withTimeout(TEST_TIMEOUT_MILLIS) { pagingStarted.await() }

                assertTrue(controller.dispatch(FilesBrowserEvent.Refresh))

                withTimeout(TEST_TIMEOUT_MILLIS) { pagingCancelled.await() }
                val refreshed = controller.awaitState {
                    val content = it.current.content as? FilesContent.Ready
                    content?.items?.singleOrNull()?.name == "fresh.mkv"
                }
                assertEquals(FilesFolderOperation.Idle, refreshed.current.operation)
            } finally {
                controller.close()
            }
        }

    private suspend fun FilesBrowserController.awaitState(
        predicate: (FilesBrowserState) -> Boolean,
    ): FilesBrowserState = withTimeout(TEST_TIMEOUT_MILLIS) { state.first(predicate) }

    private fun item(
        id: Long,
        name: String,
        type: PutioFileType,
    ): FilesItem =
        FilesItem(
            id = FilesItemId(id),
            parentId = FilesItemId(0L),
            name = name,
            type = type,
            sizeBytes = 1L,
            createdAt = "2026-08-29T00:00:00Z",
        )

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 2_000L
    }
}
