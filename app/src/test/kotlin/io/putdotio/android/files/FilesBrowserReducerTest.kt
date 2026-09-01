package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesBrowserReducerTest {

    @Test
    fun startsByLoadingRootFolderZero() {
        val transition = FilesBrowserReducer.start()

        val effect = transition.effect as FilesBrowserEffect.LoadFolder
        assertEquals(FilesItemId(0L), effect.folderId)
        assertEquals(effect.requestId, (transition.state.current.content as FilesContent.Loading).requestId)
        assertFalse(transition.state.canNavigateBack)
    }

    @Test
    fun exposesEmptyAndRetryableInitialFailureStates() {
        val start = FilesBrowserReducer.start()
        val request = start.effect as FilesBrowserEffect.LoadFolder
        val empty =
            FilesBrowserReducer.reduce(
                start.state,
                FilesBrowserEvent.LoadSucceeded(request.requestId, FilesPage(emptyList(), null)),
            )
        assertTrue(empty.state.current.content is FilesContent.Empty)

        val failure = FilesFailure.Unexpected(IllegalStateException("offline"))
        val failed =
            FilesBrowserReducer.reduce(
                start.state,
                FilesBrowserEvent.LoadFailed(request.requestId, failure),
            )
        assertEquals(failure, (failed.state.current.content as FilesContent.Failed).failure)

        val retry = FilesBrowserReducer.reduce(failed.state, FilesBrowserEvent.Retry)
        val retryEffect = retry.effect as FilesBrowserEffect.LoadFolder
        assertEquals(FilesItemId(0L), retryEffect.folderId)
        assertEquals(retryEffect.requestId, (retry.state.current.content as FilesContent.Loading).requestId)
    }

    @Test
    fun nestedBackRestoresParentItemsPagingAndViewport() {
        val folder = item(id = 7L, name = "Shows", type = PutioFileType.FOLDER)
        val root = loadedRoot(items = listOf(folder), nextCursor = FilesCursor("root-next"))
        val remembered =
            FilesBrowserReducer.reduce(
                root,
                FilesBrowserEvent.ViewportChanged(FilesViewportPosition(4, 18)),
            ).state
        val opened = FilesBrowserReducer.reduce(remembered, FilesBrowserEvent.OpenFolder(folder.id))
        val childRequest = opened.effect as FilesBrowserEffect.LoadFolder
        val childLoaded =
            FilesBrowserReducer.reduce(
                opened.state,
                FilesBrowserEvent.LoadSucceeded(
                    childRequest.requestId,
                    FilesPage(listOf(item(8L, "episode.mkv", PutioFileType.VIDEO)), null),
                ),
            ).state

        val back = FilesBrowserReducer.reduce(childLoaded, FilesBrowserEvent.NavigateBack)

        assertTrue(back.consumed)
        assertEquals(FilesFolder.Root, back.state.current.folder)
        val restored = back.state.current.content as FilesContent.Ready
        assertEquals(listOf(folder), restored.items)
        assertEquals(FilesPaging.Available(FilesCursor("root-next")), restored.paging)
        assertEquals(FilesViewportPosition(4, 18), restored.viewport)
    }

    @Test
    fun parentPagingCanFinishWhileAChildIsOpen() {
        val folder = item(id = 7L, name = "Shows", type = PutioFileType.FOLDER)
        val root = loadedRoot(items = listOf(folder), nextCursor = FilesCursor("next"))
        val paging = FilesBrowserReducer.reduce(root, FilesBrowserEvent.LoadNextPage)
        val pagingEffect = paging.effect as FilesBrowserEffect.LoadNextPage
        val opened = FilesBrowserReducer.reduce(paging.state, FilesBrowserEvent.OpenFolder(folder.id))
        val parentFinished =
            FilesBrowserReducer.reduce(
                opened.state,
                FilesBrowserEvent.LoadSucceeded(
                    pagingEffect.requestId,
                    FilesPage(listOf(item(9L, "movie.mkv", PutioFileType.VIDEO)), null),
                ),
            ).state

        val restored =
            FilesBrowserReducer.reduce(parentFinished, FilesBrowserEvent.NavigateBack)
                .state.current.content as FilesContent.Ready

        assertEquals(listOf(7L, 9L), restored.items.map { it.id.value })
        assertEquals(FilesPaging.Complete, restored.paging)
    }

    @Test
    fun ignoresDuplicatePagingWhileARequestIsInFlightAndDeduplicatesItems() {
        val existing = item(1L, "one.mkv", PutioFileType.VIDEO)
        val root = loadedRoot(items = listOf(existing), nextCursor = FilesCursor("next"))
        val first = FilesBrowserReducer.reduce(root, FilesBrowserEvent.LoadNextPage)
        val firstEffect = first.effect as FilesBrowserEffect.LoadNextPage

        val duplicateRequest = FilesBrowserReducer.reduce(first.state, FilesBrowserEvent.LoadNextPage)
        assertNull(duplicateRequest.effect)
        assertSame(first.state, duplicateRequest.state)

        val page =
            FilesPage(
                items = listOf(existing.copy(name = "server duplicate"), item(2L, "two.mkv", PutioFileType.VIDEO)),
                nextCursor = FilesCursor("later"),
            )
        val loaded =
            FilesBrowserReducer.reduce(
                first.state,
                FilesBrowserEvent.LoadSucceeded(firstEffect.requestId, page),
            )
        val content = loaded.state.current.content as FilesContent.Ready
        assertEquals(listOf(1L, 2L), content.items.map { it.id.value })
        assertEquals("one.mkv", content.items.first().name)

        val duplicateResult =
            FilesBrowserReducer.reduce(
                loaded.state,
                FilesBrowserEvent.LoadSucceeded(firstEffect.requestId, page),
            )
        assertFalse(duplicateResult.consumed)
        assertSame(loaded.state, duplicateResult.state)
    }

    @Test
    fun stopsPagingWhenTheServerCyclesToAConsumedCursor() {
        val root = loadedRoot(items = listOf(item(1L, "one", PutioFileType.FILE)), nextCursor = FilesCursor("one"))
        val first = FilesBrowserReducer.reduce(root, FilesBrowserEvent.LoadNextPage)
        val firstEffect = first.effect as FilesBrowserEffect.LoadNextPage
        val secondAvailable =
            FilesBrowserReducer.reduce(
                first.state,
                FilesBrowserEvent.LoadSucceeded(
                    firstEffect.requestId,
                    FilesPage(listOf(item(2L, "two", PutioFileType.FILE)), FilesCursor("two")),
                ),
            ).state
        val second = FilesBrowserReducer.reduce(secondAvailable, FilesBrowserEvent.LoadNextPage)
        val secondEffect = second.effect as FilesBrowserEffect.LoadNextPage
        val cycled =
            FilesBrowserReducer.reduce(
                second.state,
                FilesBrowserEvent.LoadSucceeded(
                    secondEffect.requestId,
                    FilesPage(listOf(item(3L, "three", PutioFileType.FILE)), FilesCursor("one")),
                ),
            ).state

        assertEquals(FilesPaging.Complete, (cycled.current.content as FilesContent.Ready).paging)
    }

    @Test
    fun retriesOnlyTheFailedContinuation() {
        val root = loadedRoot(items = listOf(item(1L, "one", PutioFileType.FILE)), nextCursor = FilesCursor("next"))
        val loading = FilesBrowserReducer.reduce(root, FilesBrowserEvent.LoadNextPage)
        val effect = loading.effect as FilesBrowserEffect.LoadNextPage
        val failure = FilesFailure.Unexpected(IllegalStateException("offline"))
        val failed =
            FilesBrowserReducer.reduce(
                loading.state,
                FilesBrowserEvent.LoadFailed(effect.requestId, failure),
            ).state

        assertTrue((failed.current.content as FilesContent.Ready).paging is FilesPaging.Failed)
        assertNull(FilesBrowserReducer.reduce(failed, FilesBrowserEvent.LoadNextPage).effect)

        val retried = FilesBrowserReducer.reduce(failed, FilesBrowserEvent.Retry)
        val retryEffect = retried.effect as FilesBrowserEffect.LoadNextPage
        assertEquals(FilesCursor("next"), retryEffect.cursor)
        assertTrue((retried.state.current.content as FilesContent.Ready).paging is FilesPaging.Loading)
    }

    @Test
    fun ignoresAChildResultAfterBackRemovedItsFolder() {
        val folder = item(id = 7L, name = "Shows", type = PutioFileType.FOLDER)
        val root = loadedRoot(items = listOf(folder), nextCursor = null)
        val opened = FilesBrowserReducer.reduce(root, FilesBrowserEvent.OpenFolder(folder.id))
        val childRequest = opened.effect as FilesBrowserEffect.LoadFolder
        val backedOut = FilesBrowserReducer.reduce(opened.state, FilesBrowserEvent.NavigateBack).state

        val late =
            FilesBrowserReducer.reduce(
                backedOut,
                FilesBrowserEvent.LoadSucceeded(childRequest.requestId, FilesPage(emptyList(), null)),
            )

        assertFalse(late.consumed)
        assertSame(backedOut, late.state)
    }

    @Test
    fun refreshKeepsVisibleItemsAndReplacesTheFirstPage() {
        val oldItem = item(1L, "old.mkv", PutioFileType.VIDEO)
        val freshItem = item(2L, "fresh.mkv", PutioFileType.VIDEO)
        val viewport = FilesViewportPosition(4, 18)
        val root =
            FilesBrowserReducer.reduce(
                loadedRoot(listOf(oldItem), FilesCursor("old-next")),
                FilesBrowserEvent.ViewportChanged(viewport),
            ).state
        val paging = FilesBrowserReducer.reduce(root, FilesBrowserEvent.LoadNextPage)
        val stalePagingRequest = (paging.effect as FilesBrowserEffect.LoadNextPage).requestId

        val refreshing = FilesBrowserReducer.reduce(paging.state, FilesBrowserEvent.Refresh)
        val refreshEffect = refreshing.effect as FilesBrowserEffect.LoadFolder
        val visible = refreshing.state.current.content as FilesContent.Ready

        assertEquals(listOf(oldItem), visible.items)
        assertEquals(FilesPaging.Available(FilesCursor("old-next")), visible.paging)
        assertEquals(
            FilesFolderOperation.Loading(
                refreshEffect.requestId,
                FilesFolderOperationIntent.Refresh,
                FilesFolderOperationPhase.RELOADING,
            ),
            refreshing.state.current.operation,
        )

        val stale =
            FilesBrowserReducer.reduce(
                refreshing.state,
                FilesBrowserEvent.LoadSucceeded(stalePagingRequest, FilesPage(listOf(oldItem), null)),
            )
        assertFalse(stale.consumed)
        assertSame(refreshing.state, stale.state)

        val refreshed =
            FilesBrowserReducer.reduce(
                refreshing.state,
                FilesBrowserEvent.LoadSucceeded(
                    refreshEffect.requestId,
                    FilesPage(listOf(freshItem), FilesCursor("fresh-next"), FilesSort.DATE_ADDED_DESCENDING),
                ),
            ).state
        val refreshedContent = refreshed.current.content as FilesContent.Ready
        assertEquals(listOf(freshItem), refreshedContent.items)
        assertEquals(FilesPaging.Available(FilesCursor("fresh-next")), refreshedContent.paging)
        assertEquals(viewport, refreshedContent.viewport)
        assertEquals(FilesSort.DATE_ADDED_DESCENDING, refreshed.current.folder.sort)
        assertEquals(0L, refreshed.current.viewportGeneration)
        assertEquals(FilesFolderOperation.Idle, refreshed.current.operation)
    }

    @Test
    fun sortPersistsThenReloadsAndResetsTheViewport() {
        val original = item(1L, "one.mkv", PutioFileType.VIDEO)
        val sorted = item(2L, "two.mkv", PutioFileType.VIDEO)
        val root =
            FilesBrowserReducer.reduce(
                loadedRoot(listOf(original), null, FilesSort.NAME_ASCENDING),
                FilesBrowserEvent.ViewportChanged(FilesViewportPosition(7, 20)),
            ).state

        val persisting =
            FilesBrowserReducer.reduce(
                root,
                FilesBrowserEvent.SelectSort(FilesSort.SIZE_DESCENDING),
            )
        val persistEffect = persisting.effect as FilesBrowserEffect.PersistSort
        assertEquals(FilesItemId(0L), persistEffect.folderId)
        assertEquals(FilesSort.SIZE_DESCENDING, persistEffect.sort)
        assertEquals(listOf(original), (persisting.state.current.content as FilesContent.Ready).items)

        val reloading =
            FilesBrowserReducer.reduce(
                persisting.state,
                FilesBrowserEvent.SortPersisted(persistEffect.requestId),
            )
        val reloadEffect = reloading.effect as FilesBrowserEffect.LoadFolder
        assertEquals(FilesSort.NAME_ASCENDING, reloading.state.current.folder.sort)
        assertEquals(
            FilesFolderOperationPhase.RELOADING,
            (reloading.state.current.operation as FilesFolderOperation.Loading).phase,
        )

        val completed =
            FilesBrowserReducer.reduce(
                reloading.state,
                FilesBrowserEvent.LoadSucceeded(
                    reloadEffect.requestId,
                    FilesPage(listOf(sorted), null, FilesSort.SIZE_DESCENDING),
                ),
            ).state
        val content = completed.current.content as FilesContent.Ready
        assertEquals(listOf(sorted), content.items)
        assertEquals(FilesViewportPosition(), content.viewport)
        assertEquals(FilesSort.SIZE_DESCENDING, completed.current.folder.sort)
        assertEquals(1L, completed.current.viewportGeneration)
        assertEquals(FilesFolderOperation.Idle, completed.current.operation)
    }

    @Test
    fun failedSortKeepsRowsAndRetriesTheFailedPhase() {
        val original = item(1L, "one.mkv", PutioFileType.VIDEO)
        val root = loadedRoot(listOf(original), FilesCursor("next"), FilesSort.NAME_ASCENDING)
        val persisting =
            FilesBrowserReducer.reduce(
                root,
                FilesBrowserEvent.SelectSort(FilesSort.TYPE_ASCENDING),
            )
        val persistEffect = persisting.effect as FilesBrowserEffect.PersistSort
        val failure = FilesFailure.Unexpected(IllegalStateException("offline"))
        val failed =
            FilesBrowserReducer.reduce(
                persisting.state,
                FilesBrowserEvent.LoadFailed(persistEffect.requestId, failure),
            ).state

        assertEquals(listOf(original), (failed.current.content as FilesContent.Ready).items)
        assertEquals(
            FilesFolderOperation.Failed(
                failure,
                FilesFolderOperationIntent.Sort(FilesSort.TYPE_ASCENDING),
                FilesFolderOperationPhase.PERSISTING_SORT,
            ),
            failed.current.operation,
        )

        val pagingWhileFailed = FilesBrowserReducer.reduce(failed, FilesBrowserEvent.LoadNextPage)
        assertFalse(pagingWhileFailed.consumed)
        assertNull(pagingWhileFailed.effect)
        assertSame(failed, pagingWhileFailed.state)

        val retried = FilesBrowserReducer.reduce(failed, FilesBrowserEvent.Retry)
        val retryEffect = retried.effect as FilesBrowserEffect.PersistSort
        assertEquals(FilesSort.TYPE_ASCENDING, retryEffect.sort)
    }

    @Test
    fun failedSortReloadCanRevertToTheDisplayedSort() {
        val original = item(1L, "one.mkv", PutioFileType.VIDEO)
        val root = loadedRoot(listOf(original), null, FilesSort.NAME_ASCENDING)
        val persisting = FilesBrowserReducer.reduce(
            root,
            FilesBrowserEvent.SelectSort(FilesSort.SIZE_DESCENDING),
        )
        val persistEffect = persisting.effect as FilesBrowserEffect.PersistSort
        val reloading = FilesBrowserReducer.reduce(
            persisting.state,
            FilesBrowserEvent.SortPersisted(persistEffect.requestId),
        )
        val reloadEffect = reloading.effect as FilesBrowserEffect.LoadFolder
        val failed = FilesBrowserReducer.reduce(
            reloading.state,
            FilesBrowserEvent.LoadFailed(
                reloadEffect.requestId,
                FilesFailure.Unexpected(IllegalStateException("offline")),
            ),
        ).state

        val reverted = FilesBrowserReducer.reduce(
            failed,
            FilesBrowserEvent.SelectSort(FilesSort.NAME_ASCENDING),
        )
        val revertEffect = reverted.effect as FilesBrowserEffect.PersistSort

        assertEquals(FilesSort.NAME_ASCENDING, revertEffect.sort)
        assertEquals(
            FilesFolderOperationIntent.Sort(FilesSort.NAME_ASCENDING),
            (reverted.state.current.operation as FilesFolderOperation.Loading).intent,
        )
    }

    @Test
    fun failedRefreshKeepsRowsAndRetriesTheReload() {
        val original = item(1L, "one.mkv", PutioFileType.VIDEO)
        val root = loadedRoot(listOf(original), FilesCursor("next"), FilesSort.NAME_ASCENDING)
        val refreshing = FilesBrowserReducer.reduce(root, FilesBrowserEvent.Refresh)
        val refreshEffect = refreshing.effect as FilesBrowserEffect.LoadFolder
        val failure = FilesFailure.Unexpected(IllegalStateException("offline"))
        val failed =
            FilesBrowserReducer.reduce(
                refreshing.state,
                FilesBrowserEvent.LoadFailed(refreshEffect.requestId, failure),
            ).state

        assertEquals(listOf(original), (failed.current.content as FilesContent.Ready).items)
        assertEquals(
            FilesFolderOperation.Failed(
                failure,
                FilesFolderOperationIntent.Refresh,
                FilesFolderOperationPhase.RELOADING,
            ),
            failed.current.operation,
        )

        val retried = FilesBrowserReducer.reduce(failed, FilesBrowserEvent.Retry)
        val retryEffect = retried.effect as FilesBrowserEffect.LoadFolder
        assertEquals(FilesFolder.Root.id, retryEffect.folderId)
        assertEquals(
            FilesFolderOperationIntent.Refresh,
            (retried.state.current.operation as FilesFolderOperation.Loading).intent,
        )
    }

    @Test
    fun pagingRetryCannotRaceAnActiveRefresh() {
        val original = item(1L, "one.mkv", PutioFileType.VIDEO)
        val root = loadedRoot(listOf(original), FilesCursor("next"))
        val paging = FilesBrowserReducer.reduce(root, FilesBrowserEvent.LoadNextPage)
        val pagingEffect = paging.effect as FilesBrowserEffect.LoadNextPage
        val pagingFailure = FilesFailure.Unexpected(IllegalStateException("offline"))
        val failedPaging =
            FilesBrowserReducer.reduce(
                paging.state,
                FilesBrowserEvent.LoadFailed(pagingEffect.requestId, pagingFailure),
            ).state
        val refreshing = FilesBrowserReducer.reduce(failedPaging, FilesBrowserEvent.Refresh)

        val retryWhileRefreshing = FilesBrowserReducer.reduce(refreshing.state, FilesBrowserEvent.Retry)

        assertFalse(retryWhileRefreshing.consumed)
        assertNull(retryWhileRefreshing.effect)
        assertSame(refreshing.state, retryWhileRefreshing.state)
    }

    @Test
    fun failedSortReloadRetriesWithoutPersistingAgainAndRejectsLateResults() {
        val original = item(1L, "one.mkv", PutioFileType.VIDEO)
        val root = loadedRoot(listOf(original), null, FilesSort.NAME_ASCENDING)
        val persisting =
            FilesBrowserReducer.reduce(
                root,
                FilesBrowserEvent.SelectSort(FilesSort.SIZE_ASCENDING),
            )
        val persistEffect = persisting.effect as FilesBrowserEffect.PersistSort
        val reloading =
            FilesBrowserReducer.reduce(
                persisting.state,
                FilesBrowserEvent.SortPersisted(persistEffect.requestId),
            )
        val reloadEffect = reloading.effect as FilesBrowserEffect.LoadFolder
        val latePersist =
            FilesBrowserReducer.reduce(
                reloading.state,
                FilesBrowserEvent.SortPersisted(persistEffect.requestId),
            )
        assertFalse(latePersist.consumed)
        assertSame(reloading.state, latePersist.state)

        val failure = FilesFailure.Unexpected(IllegalStateException("offline"))
        val failed =
            FilesBrowserReducer.reduce(
                reloading.state,
                FilesBrowserEvent.LoadFailed(reloadEffect.requestId, failure),
            ).state
        val retried = FilesBrowserReducer.reduce(failed, FilesBrowserEvent.Retry)

        assertTrue(retried.effect is FilesBrowserEffect.LoadFolder)
        assertEquals(
            FilesFolderOperationPhase.RELOADING,
            (retried.state.current.operation as FilesFolderOperation.Loading).phase,
        )
    }

    @Test
    fun leavesRootBackForTheHostToHandle() {
        val root = loadedRoot(items = emptyList(), nextCursor = null)

        val back = FilesBrowserReducer.reduce(root, FilesBrowserEvent.NavigateBack)

        assertFalse(back.consumed)
        assertSame(root, back.state)
    }

    private fun loadedRoot(
        items: List<FilesItem>,
        nextCursor: FilesCursor?,
        sort: FilesSort? = null,
    ): FilesBrowserState {
        val start = FilesBrowserReducer.start()
        val request = start.effect as FilesBrowserEffect.LoadFolder
        return FilesBrowserReducer.reduce(
            start.state,
            FilesBrowserEvent.LoadSucceeded(request.requestId, FilesPage(items, nextCursor, sort)),
        ).state
    }

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
}
