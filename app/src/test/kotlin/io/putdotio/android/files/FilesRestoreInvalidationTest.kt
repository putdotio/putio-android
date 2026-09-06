package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesRestoreInvalidationTest {
    private val parent = FilesFolder(FilesItemId(10), "Parent", FilesSort.NAME_DESCENDING)
    private val child = FilesFolder(FilesItemId(11), "Child")
    private val restored = item(99, parent.id)
    private val viewport = FilesViewportPosition(3, 17)
    private val readFailure = FilesFailure.Unexpected(IllegalStateException("Read offline"))

    @Test
    fun restorationInvalidatesOnlyTheReturnedParentAndRootWithoutNavigating() {
        val original = nested()
        val invalidated = FilesBrowserReducer.reduce(original, FilesBrowserEvent.InvalidateRestoredItem(restored))

        assertNull(invalidated.effect)
        assertEquals(original.path, invalidated.state.path)
        assertSame(original.current, invalidated.state.current)
        assertTrue(invalidated.state.stack[0].needsReload)
        assertTrue(invalidated.state.stack[1].needsReload)
        assertFalse(invalidated.state.current.needsReload)
        assertSame(original.current, FilesBrowserReducer.reduce(invalidated.state,
            FilesBrowserEvent.ReloadIfStale).state.current)

        for (returnedParent in listOf(FilesFolder.Root.id, FilesItemId(500))) {
            val rootOnly = invalidate(original, restored.copy(parentId = returnedParent))
            assertTrue(rootOnly.stack[0].needsReload)
            assertSame(original.stack[1], rootOnly.stack[1])
            assertSame(original.current, rootOnly.current)
        }
    }

    @Test
    fun revisitingCachedParentAndRootReloadsEachAndKeepsSortAndViewport() {
        val invalidated = invalidate(nested())
        val parentBack = FilesBrowserReducer.reduce(invalidated, FilesBrowserEvent.NavigateBack)
        assertEquals(parent.id, (parentBack.effect as FilesBrowserEffect.LoadFolder).folderId)
        assertEquals(viewport, (parentBack.state.current.content as FilesContent.Ready).viewport)
        assertFalse(parentBack.state.current.needsReload)
        val parentLoaded = finish(parentBack, listOf(restored))
        assertEquals(parent.sort, parentLoaded.current.folder.sort)
        assertEquals(viewport, (parentLoaded.current.content as FilesContent.Ready).viewport)

        val rootBack = FilesBrowserReducer.reduce(parentLoaded, FilesBrowserEvent.NavigateBack)
        assertEquals(FilesFolder.Root.id, (rootBack.effect as FilesBrowserEffect.LoadFolder).folderId)
        val rootLoaded = finish(rootBack, listOf(item(parent.id.value, FilesFolder.Root.id)))
        assertFalse(rootLoaded.current.needsReload)
        assertNull(FilesBrowserReducer.reduce(rootLoaded, FilesBrowserEvent.ReloadIfStale).effect)
    }

    @Test
    fun visibleStaleFolderReloadsOnceWithoutDroppingRowsOrViewport() {
        val original = nested().copy(stack = nested().stack.take(2))
        val loading = FilesBrowserReducer.reduce(invalidate(original), FilesBrowserEvent.ReloadIfStale)
        assertEquals(parent.id, (loading.effect as FilesBrowserEffect.LoadFolder).folderId)
        assertEquals(original.current.content, loading.state.current.content)
        assertNull(FilesBrowserReducer.reduce(loading.state, FilesBrowserEvent.ReloadIfStale).effect)
        val loaded = finish(loading, listOf(restored))
        assertEquals(listOf(restored), loaded.current.content.items())
        assertEquals(viewport, (loaded.current.content as FilesContent.Ready).viewport)
        assertFalse(FilesBrowserReducer.reduce(loaded, FilesBrowserEvent.ReloadIfStale).consumed)
    }

    @Test
    fun restoreDuringOlderReloadSurvivesItsCompletionAndRejectsItsLateResult() {
        val root = nested().copy(stack = nested().stack.take(1))
        val oldRead = FilesBrowserReducer.reduce(root, FilesBrowserEvent.Refresh)
        val invalidated = invalidate(oldRead.state, restored.copy(parentId = FilesFolder.Root.id))
        assertSame(oldRead.state.current.operation, invalidated.current.operation)
        assertNull(FilesBrowserReducer.reduce(invalidated, FilesBrowserEvent.ReloadIfStale).effect)
        val staleResult = FilesBrowserReducer.reduce(invalidated, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(oldRead.effect).requestId, FilesPage(root.current.content.items(), null),
        )).state
        assertTrue(staleResult.current.needsReload)
        val newRead = FilesBrowserReducer.reduce(staleResult, FilesBrowserEvent.ReloadIfStale)
        assertTrue(newRead.effect is FilesBrowserEffect.LoadFolder)
        assertFalse(newRead.state.current.needsReload)
        val late = FilesBrowserReducer.reduce(newRead.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(oldRead.effect).requestId, FilesPage(emptyList(), null),
        ))
        assertFalse(late.consumed)
        assertSame(newRead.state, late.state)
        assertEquals(listOf(restored), finish(newRead, listOf(restored)).current.content.items())
    }

    @Test
    fun loadingAndFailedItemOperationsKeepTheirRecoveryAndRequests() {
        val operations = listOf(
            FilesFolderOperationIntent.Move(restored.id, FilesItemId(700)) to FilesFolderOperationPhase.MOVING,
            FilesFolderOperationIntent.Delete(restored.id, FilesDeleteMode.TRASH) to FilesFolderOperationPhase.DELETING,
            FilesFolderOperationIntent.Rename(restored.id, "Renamed") to FilesFolderOperationPhase.RENAMING,
        )
        for ((intent, phase) in operations) {
            for (operation in listOf(
                FilesFolderOperation.Loading(FilesRequestId(50), intent, phase),
                FilesFolderOperation.Failed(readFailure, intent, phase),
            )) {
                val original = FilesBrowserState(listOf(nested().stack[1].copy(operation = operation)), 100)
                val invalidated = invalidate(original)
                assertSame(operation, invalidated.current.operation)
                assertSame(original.current.content, invalidated.current.content)
                val deferred = FilesBrowserReducer.reduce(invalidated, FilesBrowserEvent.ReloadIfStale)
                assertNull(deferred.effect)
                assertFalse(deferred.consumed)
                assertSame(invalidated, deferred.state)
                assertTrue(deferred.state.current.needsReload)
                assertEquals(original.hasRequest(FilesRequestId(50)), deferred.state.hasRequest(FilesRequestId(50)))
            }
        }
    }

    @Test
    fun returningToStaleParentDoesNotReplaceItsPendingRename() {
        val rename = FilesFolderOperation.Loading(FilesRequestId(50),
            FilesFolderOperationIntent.Rename(restored.id, "Renamed"), FilesFolderOperationPhase.RENAMING)
        val original = nested()
        val pending = original.copy(stack = original.stack.replaceAt(1, original.stack[1].copy(operation = rename)))
        val back = FilesBrowserReducer.reduce(invalidate(pending), FilesBrowserEvent.NavigateBack)
        assertTrue(back.consumed)
        assertEquals(parent.id, back.state.current.folder.id)
        assertSame(rename, back.state.current.operation)
        assertNull(back.effect)
        assertTrue(back.state.current.needsReload)
        assertTrue(back.state.hasRequest(FilesRequestId(50)))
    }

    @Test
    fun initialReadAndPagingCannotConsumeTheLaterRestoreInvalidation() {
        val initial = FilesBrowserReducer.start()
        val invalidated = invalidate(initial.state, restored.copy(parentId = FilesFolder.Root.id))
        assertNull(FilesBrowserReducer.reduce(invalidated, FilesBrowserEvent.ReloadIfStale).effect)
        assertTrue(invalidated.hasRequest(checkNotNull(initial.effect).requestId))
        val loaded = finish(initial.copy(state = invalidated), listOf(item(7, FilesFolder.Root.id)))
        assertTrue(loaded.current.needsReload)
        val reload = FilesBrowserReducer.reduce(loaded, FilesBrowserEvent.ReloadIfStale)
        assertTrue(reload.effect is FilesBrowserEffect.LoadFolder)

        val ready = nested().stack[1].copy(content = FilesContent.Ready(listOf(restored),
            FilesPaging.Available(FilesCursor("next-page")), viewport))
        val paging = FilesBrowserReducer.reduce(FilesBrowserState(listOf(ready), 100), FilesBrowserEvent.LoadNextPage)
        val pagingReload = FilesBrowserReducer.reduce(invalidate(paging.state), FilesBrowserEvent.ReloadIfStale)
        assertTrue(pagingReload.effect is FilesBrowserEffect.LoadFolder)
        assertFalse(pagingReload.state.hasRequest(checkNotNull(paging.effect).requestId))
        assertFalse(FilesBrowserReducer.reduce(pagingReload.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(paging.effect).requestId, FilesPage(listOf(item(500, parent.id)), null),
        )).consumed)
    }

    @Test
    fun failedReloadRequiresExplicitReadRetryAndRetainsLaterInvalidation() {
        val original = FilesBrowserState(listOf(nested().stack[1]), 100)
        val reload = FilesBrowserReducer.reduce(invalidate(original), FilesBrowserEvent.ReloadIfStale)
        val failed = FilesBrowserReducer.reduce(reload.state, FilesBrowserEvent.LoadFailed(
            checkNotNull(reload.effect).requestId, readFailure,
        )).state
        val invalidated = invalidate(failed)
        assertNull(FilesBrowserReducer.reduce(invalidated, FilesBrowserEvent.ReloadIfStale).effect)
        val retry = FilesBrowserReducer.reduce(invalidated, FilesBrowserEvent.Retry)
        assertTrue(retry.effect is FilesBrowserEffect.LoadFolder)
        assertFalse(retry.state.current.needsReload)
        assertEquals(listOf(restored), finish(retry, listOf(restored)).current.content.items())
    }

    @Test
    fun invalidRestoredIdentityCannotInvalidateTheCache() {
        val original = nested()
        for (invalid in listOf(restored.copy(id = FilesItemId(0)), restored.copy(id = FilesItemId(-1)),
            restored.copy(parentId = null), restored.copy(parentId = FilesItemId(-1)))) {
            val result = FilesBrowserReducer.reduce(original, FilesBrowserEvent.InvalidateRestoredItem(invalid))
            assertSame(original, result.state)
            assertFalse(result.consumed)
            assertNull(result.effect)
        }
    }

    private fun invalidate(state: FilesBrowserState, item: FilesItem = restored): FilesBrowserState =
        FilesBrowserReducer.reduce(state, FilesBrowserEvent.InvalidateRestoredItem(item)).state

    private fun finish(transition: FilesBrowserTransition, items: List<FilesItem>): FilesBrowserState =
        FilesBrowserReducer.reduce(transition.state, FilesBrowserEvent.LoadSucceeded(
            checkNotNull(transition.effect).requestId, FilesPage(items, null),
        )).state

    private fun nested(): FilesBrowserState = FilesBrowserState(listOf(
        FilesFolderState(FilesFolder.Root, FilesContent.Ready(listOf(item(parent.id.value, FilesFolder.Root.id)),
            FilesPaging.Complete, viewport)),
        FilesFolderState(parent, FilesContent.Ready(
            listOf(item(child.id.value, parent.id)), FilesPaging.Complete, viewport)),
        FilesFolderState(child, FilesContent.Ready(listOf(item(20, child.id)), FilesPaging.Complete, viewport)),
    ), 100)

    private fun item(id: Long, parentId: FilesItemId): FilesItem =
        FilesItem(FilesItemId(id), parentId, "Item $id", PutioFileType.FOLDER, 0, "2026-09-06")

    @Test
    fun bulkRestoreInvalidatesEveryCachedLevelWithoutNavigatingOrReloadingEagerly() {
        val original = nested()
        val invalidated = FilesBrowserReducer.reduce(original, FilesBrowserEvent.InvalidateAllFolders)
        assertNull(invalidated.effect)
        assertEquals(original.path, invalidated.state.path)
        assertTrue(invalidated.state.stack.all { it.needsReload })
        assertEquals(original.current.content, invalidated.state.current.content)
        val reloading = FilesBrowserReducer.reduce(invalidated.state, FilesBrowserEvent.ReloadIfStale)
        assertEquals(child.id, (reloading.effect as FilesBrowserEffect.LoadFolder).folderId)
        assertFalse(reloading.state.current.needsReload)
        assertTrue(reloading.state.stack[0].needsReload)
        assertTrue(reloading.state.stack[1].needsReload)
    }
}
