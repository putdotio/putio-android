package io.putdotio.android.tv

import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.trash.TrashBulkSelection
import io.putdotio.android.trash.TrashPage
import io.putdotio.android.trash.TrashRepository
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvTrashUndoTest {
    @Test
    fun `an undo while another restore is still checking restores its item too`() = runTest {
        val firstReadback = CompletableDeferred<Unit>()
        val restored = mutableListOf<Long>()
        val trash = object : TrashRepository {
            override suspend fun load() = PutioResult.Success(TrashPage(emptyList(), nextCursor = null))
            override suspend fun loadNextPage(cursor: FilesCursor) = error("No continuation expected")
            override suspend fun restore(itemId: FilesItemId): PutioResult<Unit> {
                restored += itemId.value
                return PutioResult.Success(Unit)
            }
            override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> {
                if (itemId.value == 1L) firstReadback.await()
                return PutioResult.Success(item(itemId.value))
            }
            override suspend fun deleteItem(itemId: FilesItemId) = error("No delete expected")
            override suspend fun restoreAll(selection: TrashBulkSelection) = error("No bulk restore expected")
            override suspend fun empty() = error("No empty expected")
        }
        val files = FilesBrowserController(
            object : StubFilesRepository() {
                override suspend fun loadFolder(folderId: FilesItemId) =
                    PutioResult.Success(FilesPage(emptyList(), null))
            },
            backgroundScope,
        )
        val undo = TvTrashUndo(trash, files, backgroundScope)

        undo.undo(trashed(1))
        runCurrent()
        undo.undo(trashed(2))
        runCurrent()
        firstReadback.complete(Unit)
        runCurrent()

        assertEquals(listOf(1L, 2L), restored)
        assertNull(undo.failure.value)
    }

    private fun trashed(id: Long) = FilesDeleteOutcome(
        FilesRequestId(id),
        FilesFolderOperationIntent.Delete(FilesItemId(id), FilesDeleteMode.TRASH),
        "item-$id",
    )

    private fun item(id: Long) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesItemId(0L),
        name = "item-$id",
        type = PutioFileType.VIDEO,
        sizeBytes = 1L,
        createdAt = "2026-04-20T10:00:00Z",
    )
}
