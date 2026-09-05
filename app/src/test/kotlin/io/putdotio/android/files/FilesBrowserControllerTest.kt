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
    fun renameOwnsItsRequestCancelsPagingAndRetriesOnlyFailedReload() = runBlocking {
        val original = item(7L, "old.mkv", PutioFileType.VIDEO)
        val renameResult = CompletableDeferred<FilesRepositoryResult<Unit>>()
        val pagingStarted = CompletableDeferred<Unit>()
        val pagingCancelled = CompletableDeferred<Unit>()
        val renamed = mutableListOf<Pair<FilesItemId, String>>()
        var folderLoads = 0
        val repository = object : FilesRepository {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> {
                folderLoads += 1
                return when (folderLoads) {
                    1 -> FilesRepositoryResult.Success(FilesPage(listOf(original), FilesCursor("old")))
                    2 -> FilesRepositoryResult.Failure(FilesFailure.Unexpected(IllegalStateException("reload failed")))
                    else -> FilesRepositoryResult.Success(FilesPage(listOf(original.copy(name = "new.mkv")), null))
                }
            }

            override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> {
                pagingStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    pagingCancelled.complete(Unit)
                }
            }

            override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort): FilesRepositoryResult<Unit> =
                error("No sort expected")

            override suspend fun rename(itemId: FilesItemId, name: String): FilesRepositoryResult<Unit> {
                renamed += itemId to name
                return renameResult.await()
            }
        }
        val controller = FilesBrowserController(repository, this)
        try {
            controller.awaitState { it.current.content is FilesContent.Ready }
            controller.dispatch(FilesBrowserEvent.LoadNextPage)
            withTimeout(TEST_TIMEOUT_MILLIS) { pagingStarted.await() }
            val event = FilesBrowserEvent.Rename(FilesFolder.Root.id, original.id, "new.mkv")
            assertTrue(controller.dispatch(event))
            assertFalse(controller.dispatch(event))
            withTimeout(TEST_TIMEOUT_MILLIS) { pagingCancelled.await() }
            renameResult.complete(FilesRepositoryResult.Success(Unit))
            val failed = controller.awaitState { it.current.operation is FilesFolderOperation.Failed }
            assertEquals(
                FilesFolderOperationPhase.RELOADING,
                (failed.current.operation as FilesFolderOperation.Failed).phase,
            )
            assertEquals(listOf(original), (failed.current.content as FilesContent.Ready).items)
            controller.dispatch(FilesBrowserEvent.Retry)
            val completed = controller.awaitState { it.current.operation == FilesFolderOperation.Idle }
            assertEquals("new.mkv", (completed.current.content as FilesContent.Ready).items.single().name)
            assertEquals(listOf(original.id to "new.mkv"), renamed)
            assertEquals(3, folderLoads)
        } finally {
            controller.close()
        }
    }

    @Test
    fun closingCancelsAnInFlightRename() = runBlocking {
        val original = item(7L, "old.mkv", PutioFileType.VIDEO)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val repository = object : FilesRepository {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                FilesRepositoryResult.Success(FilesPage(listOf(original), null))

            override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> =
                error("No continuation expected")

            override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort): FilesRepositoryResult<Unit> =
                error("No sort expected")

            override suspend fun rename(itemId: FilesItemId, name: String): FilesRepositoryResult<Unit> {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val controller = FilesBrowserController(repository, this)
        try {
            controller.awaitState { it.current.content is FilesContent.Ready }
            controller.dispatch(FilesBrowserEvent.Rename(FilesFolder.Root.id, original.id, "new.mkv"))
            withTimeout(TEST_TIMEOUT_MILLIS) { started.await() }
        } finally {
            controller.close()
        }
        withTimeout(TEST_TIMEOUT_MILLIS) { cancelled.await() }
        assertFalse(controller.dispatch(FilesBrowserEvent.Retry))
    }

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

                    override suspend fun rename(itemId: FilesItemId, name: String): FilesRepositoryResult<Unit> =
                        error("No rename expected")

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

                    override suspend fun rename(itemId: FilesItemId, name: String): FilesRepositoryResult<Unit> =
                        error("No rename expected")

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
    fun externalNavigationCancelsRequestsRemovedWithThePreviousStack() =
        runBlocking {
            val childStarted = CompletableDeferred<Unit>()
            val childCancelled = CompletableDeferred<Unit>()
            val folder = item(7L, "Shows", PutioFileType.FOLDER)
            val repository =
                object : FilesRepository {
                    override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                        when (folderId.value) {
                            FilesFolder.Root.id.value -> FilesRepositoryResult.Success(FilesPage(listOf(folder), null))
                            folder.id.value -> {
                                childStarted.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    childCancelled.complete(Unit)
                                }
                            }
                            44L -> FilesRepositoryResult.Success(FilesPage(emptyList(), null))
                            else -> error("Unexpected folder $folderId")
                        }

                    override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> =
                        error("No continuation expected")

                    override suspend fun rename(itemId: FilesItemId, name: String): FilesRepositoryResult<Unit> =
                        error("No rename expected")

                    override suspend fun persistSort(
                        folderId: FilesItemId,
                        sort: FilesSort,
                    ): FilesRepositoryResult<Unit> = error("No sort expected")
                }
            val controller = FilesBrowserController(repository, this)

            try {
                controller.awaitState { it.current.content is FilesContent.Ready }
                assertTrue(controller.dispatch(FilesBrowserEvent.OpenFolder(folder.id)))
                withTimeout(TEST_TIMEOUT_MILLIS) { childStarted.await() }

                val externalFile = item(99L, "movie.mkv", PutioFileType.VIDEO).copy(parentId = FilesItemId(44L))
                assertTrue(controller.dispatch(FilesBrowserEvent.OpenExternalItem(externalFile)))

                withTimeout(TEST_TIMEOUT_MILLIS) { childCancelled.await() }
                controller.awaitState {
                    it.current.folder.id == FilesItemId(44L) && it.current.content is FilesContent.Empty
                }
                assertEquals(listOf(FilesItemId(0L), FilesItemId(44L)), controller.state.value.path.map { it.id })
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

                    override suspend fun rename(itemId: FilesItemId, name: String): FilesRepositoryResult<Unit> =
                        error("No rename expected")

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

                    override suspend fun rename(itemId: FilesItemId, name: String): FilesRepositoryResult<Unit> =
                        error("No rename expected")

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

    @Test
    fun sortRetriesPersistenceAndRecoversFromReloadFailure() =
        runBlocking {
            val original = item(1L, "old.mkv", PutioFileType.VIDEO)
            var folderLoads = 0
            val persistedSorts = mutableListOf<FilesSort>()
            val repository =
                object : FilesRepository {
                    override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> {
                        folderLoads += 1
                        return when (folderLoads) {
                            1 -> FilesRepositoryResult.Success(
                                FilesPage(listOf(original), null, FilesSort.NAME_ASCENDING),
                            )

                            2 -> FilesRepositoryResult.Failure(
                                FilesFailure.Unexpected(IllegalStateException("reload failed")),
                            )

                            else -> FilesRepositoryResult.Success(
                                FilesPage(listOf(original), null, FilesSort.NAME_ASCENDING),
                            )
                        }
                    }

                    override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> =
                        error("No continuation expected")

                    override suspend fun rename(itemId: FilesItemId, name: String): FilesRepositoryResult<Unit> =
                        error("No rename expected")

                    override suspend fun persistSort(
                        folderId: FilesItemId,
                        sort: FilesSort,
                    ): FilesRepositoryResult<Unit> {
                        persistedSorts += sort
                        return if (persistedSorts.size == 1) {
                            FilesRepositoryResult.Failure(
                                FilesFailure.Unexpected(IllegalStateException("persist failed")),
                            )
                        } else {
                            FilesRepositoryResult.Success(Unit)
                        }
                    }
                }
            val controller = FilesBrowserController(repository, this)

            try {
                controller.awaitState { it.current.content is FilesContent.Ready }
                assertTrue(controller.dispatch(FilesBrowserEvent.SelectSort(FilesSort.SIZE_DESCENDING)))

                val persistenceFailed = controller.awaitState {
                    (it.current.operation as? FilesFolderOperation.Failed)?.phase ==
                        FilesFolderOperationPhase.PERSISTING_SORT
                }
                assertEquals(listOf(original), (persistenceFailed.current.content as FilesContent.Ready).items)

                assertTrue(controller.dispatch(FilesBrowserEvent.Retry))
                val reloadFailed = controller.awaitState {
                    (it.current.operation as? FilesFolderOperation.Failed)?.phase ==
                        FilesFolderOperationPhase.RELOADING
                }
                assertEquals(listOf(original), (reloadFailed.current.content as FilesContent.Ready).items)

                assertTrue(controller.dispatch(FilesBrowserEvent.SelectSort(FilesSort.NAME_ASCENDING)))
                val recovered = controller.awaitState {
                    it.current.operation == FilesFolderOperation.Idle && folderLoads == 3
                }

                assertEquals(
                    listOf(
                        FilesSort.SIZE_DESCENDING,
                        FilesSort.SIZE_DESCENDING,
                        FilesSort.NAME_ASCENDING,
                    ),
                    persistedSorts,
                )
                assertEquals(FilesSort.NAME_ASCENDING, recovered.current.folder.sort)
                assertEquals(listOf(original), (recovered.current.content as FilesContent.Ready).items)
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
