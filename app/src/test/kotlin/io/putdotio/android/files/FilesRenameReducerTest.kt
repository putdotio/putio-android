package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesRenameReducerTest {

    @Test
    fun renameSupersedesPagingAndReloadsAuthoritativeOrderWithoutResettingViewport() {
        val original = item(7L, "old.mkv", PutioFileType.VIDEO)
        val viewport = FilesViewportPosition(4, 20)
        val root = FilesBrowserReducer.reduce(
            loadedRoot(listOf(original), FilesCursor("old-cursor"), FilesSort.NAME_ASCENDING),
            FilesBrowserEvent.ViewportChanged(viewport),
        ).state
        val paging = FilesBrowserReducer.reduce(root, FilesBrowserEvent.LoadNextPage)
        val pendingPage = paging.effect as FilesBrowserEffect.LoadNextPage
        val event = FilesBrowserEvent.Rename(FilesFolder.Root.id, original.id, "  Türkçe.mkv  ")
        val saving = FilesBrowserReducer.reduce(paging.state, event)
        val rename = saving.effect as FilesBrowserEffect.Rename
        assertEquals(event.name, rename.name)
        assertEquals(original.id, rename.itemId)
        assertEquals(listOf(original), (saving.state.current.content as FilesContent.Ready).items)
        val conflicts = listOf(event, FilesBrowserEvent.Refresh, FilesBrowserEvent.SelectSort(FilesSort.SIZE_ASCENDING))
        for (conflict in conflicts) {
            assertFalse(FilesBrowserReducer.reduce(saving.state, conflict).consumed)
        }
        assertFalse(
            FilesBrowserReducer.reduce(
                saving.state,
                FilesBrowserEvent.LoadSucceeded(pendingPage.requestId, FilesPage(listOf(original), null)),
            ).consumed,
        )
        val wrongCompletion = FilesBrowserEvent.LoadSucceeded(rename.requestId, FilesPage(listOf(original), null))
        assertFalse(FilesBrowserReducer.reduce(saving.state, wrongCompletion).consumed)

        val reloading = FilesBrowserReducer.reduce(saving.state, FilesBrowserEvent.MutationSucceeded(rename.requestId))
        val reload = reloading.effect as FilesBrowserEffect.LoadFolder
        val completion = FilesRenameCompletion(
            rename.requestId, FilesFolderOperationIntent.Rename(original.id, event.name),
        )
        assertEquals(completion, reloading.state.current.renameCompletion)
        assertTrue(reload.requestId != rename.requestId)
        val lateCompletion = FilesBrowserEvent.MutationSucceeded(rename.requestId)
        assertFalse(FilesBrowserReducer.reduce(reloading.state, lateCompletion).consumed)
        val serverRows = listOf(item(8L, "first.txt", PutioFileType.TEXT), original.copy(name = event.name))
        val result = FilesBrowserReducer.reduce(
            reloading.state,
            FilesBrowserEvent.LoadSucceeded(
                reload.requestId,
                FilesPage(serverRows, FilesCursor("new-cursor"), FilesSort.NAME_DESCENDING),
            ),
        ).state.current
        assertEquals(serverRows, (result.content as FilesContent.Ready).items)
        assertEquals(viewport, result.content.viewport)
        assertEquals(root.current.viewportGeneration, result.viewportGeneration)
        assertEquals(FilesPaging.Available(FilesCursor("new-cursor")), result.content.paging)
        assertEquals(FilesSort.NAME_DESCENDING, result.folder.sort)
        assertTrue(result.consumedCursors.isEmpty())
        assertEquals(completion, result.renameCompletion)
    }

    @Test
    fun renameFailureKeepsExactDraftAndReloadFailureRetriesOnlyTheRead() {
        val original = item(7L, "old.mkv", PutioFileType.VIDEO)
        val root = loadedRoot(listOf(original), null)
        val event = FilesBrowserEvent.Rename(FilesFolder.Root.id, original.id, "")
        val saving = FilesBrowserReducer.reduce(root, event)
        val request = saving.effect as FilesBrowserEffect.Rename
        val failure = FilesFailure.Unexpected(IllegalStateException("offline"))
        val failed = FilesBrowserReducer.reduce(
            saving.state,
            FilesBrowserEvent.LoadFailed(request.requestId, failure),
        ).state
        assertEquals(
            FilesFolderOperationIntent.Rename(original.id, ""),
            (failed.current.operation as FilesFolderOperation.Failed).intent,
        )
        val retry = FilesBrowserReducer.reduce(failed, FilesBrowserEvent.Retry)
        val retryRequest = retry.effect as FilesBrowserEffect.Rename
        assertEquals("", retryRequest.name)
        assertEquals(original.id, retryRequest.itemId)
        val reloading = FilesBrowserReducer.reduce(
            retry.state, FilesBrowserEvent.MutationSucceeded(retryRequest.requestId),
        )
        val reload = reloading.effect as FilesBrowserEffect.LoadFolder
        val reloadFailed = FilesBrowserReducer.reduce(
            reloading.state,
            FilesBrowserEvent.LoadFailed(reload.requestId, failure),
        ).state
        assertFalse(FilesBrowserReducer.reduce(reloadFailed, event).consumed)
        val reloadRetry = FilesBrowserReducer.reduce(reloadFailed, FilesBrowserEvent.Retry)
        assertTrue(reloadRetry.effect is FilesBrowserEffect.LoadFolder)
    }

    @Test
    fun renameRejectsSentinelsUnchangedNamesAndStaleFolderTargets() {
        val folder = item(7L, "folder", PutioFileType.FOLDER)
        val root = loadedRoot(
            listOf(folder, item(0L, "root", PutioFileType.FOLDER), item(-1L, "virtual", PutioFileType.FOLDER)),
            null,
        )
        val invalid = listOf(
            FilesBrowserEvent.Rename(FilesFolder.Root.id, FilesItemId(0L), "new"),
            FilesBrowserEvent.Rename(FilesFolder.Root.id, FilesItemId(-1L), "new"),
            FilesBrowserEvent.Rename(FilesFolder.Root.id, FilesItemId(99L), "new"),
            FilesBrowserEvent.Rename(FilesFolder.Root.id, folder.id, folder.name),
            FilesBrowserEvent.Rename(FilesItemId(44L), folder.id, "new"),
        )
        invalid.forEach { assertFalse(FilesBrowserReducer.reduce(root, it).consumed) }
        val folderRename = FilesBrowserEvent.Rename(FilesFolder.Root.id, folder.id, "new")
        assertTrue(FilesBrowserReducer.reduce(root, folderRename).consumed)
        val opening = FilesBrowserReducer.reduce(root, FilesBrowserEvent.OpenFolder(folder.id))
        val loaded = FilesBrowserReducer.reduce(
            opening.state,
            FilesBrowserEvent.LoadSucceeded(
                (opening.effect as FilesBrowserEffect.LoadFolder).requestId,
                FilesPage(listOf(item(8L, "child", PutioFileType.TEXT)), null),
            ),
        ).state
        val childRename = FilesBrowserEvent.Rename(folder.id, FilesItemId(8L), "new child")
        val saving = FilesBrowserReducer.reduce(loaded, childRename)
        val back = FilesBrowserReducer.reduce(saving.state, FilesBrowserEvent.NavigateBack).state
        val lateRename = FilesBrowserEvent.MutationSucceeded((saving.effect as FilesBrowserEffect.Rename).requestId)
        assertFalse(FilesBrowserReducer.reduce(back, lateRename).consumed)
    }

    @Test
    fun abandonmentOnlyClearsTheMatchingFailedMutationAndPreservesReloadRecovery() {
        val original = item(7L, "old.mkv", PutioFileType.VIDEO)
        val intent = FilesFolderOperationIntent.Rename(original.id, "new.mkv")
        val abandon = FilesBrowserEvent.AbandonRename(FilesFolder.Root.id, intent)
        val saving = FilesBrowserReducer.reduce(
            loadedRoot(listOf(original), FilesCursor("next")),
            FilesBrowserEvent.Rename(FilesFolder.Root.id, original.id, intent.name),
        )
        val requestId = checkNotNull(saving.effect).requestId
        assertFalse(FilesBrowserReducer.reduce(saving.state, abandon).consumed)
        val failure = FilesFailure.Unexpected(IllegalStateException("offline"))
        val failed = FilesBrowserReducer.reduce(saving.state, FilesBrowserEvent.LoadFailed(requestId, failure)).state
        assertFalse(FilesBrowserReducer.reduce(failed, abandon.copy(folderId = FilesItemId(99L))).consumed)
        assertFalse(FilesBrowserReducer.reduce(failed, abandon.copy(intent = intent.copy(name = "stale"))).consumed)
        val abandoned = FilesBrowserReducer.reduce(failed, abandon).state
        assertEquals(FilesFolderOperation.Idle, abandoned.current.operation)
        assertEquals(failed.current.content, abandoned.current.content)
        assertTrue(FilesBrowserReducer.reduce(abandoned, FilesBrowserEvent.Retry).effect == null)

        val reloading = FilesBrowserReducer.reduce(saving.state, FilesBrowserEvent.MutationSucceeded(requestId))
        val reloadFailed = FilesBrowserReducer.reduce(reloading.state, FilesBrowserEvent.LoadFailed(
            checkNotNull(reloading.effect).requestId, failure,
        )).state
        assertFalse(FilesBrowserReducer.reduce(reloadFailed, abandon).consumed)
        val retry = FilesBrowserReducer.reduce(reloadFailed, FilesBrowserEvent.Retry)
        assertTrue(retry.effect is FilesBrowserEffect.LoadFolder)
        assertEquals(reloading.state.current.renameCompletion, retry.state.current.renameCompletion)
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
