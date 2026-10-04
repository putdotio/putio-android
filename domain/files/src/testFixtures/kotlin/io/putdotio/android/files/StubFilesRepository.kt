package io.putdotio.android.files

import io.putdotio.android.PutioResult
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FileMoveError

public abstract class StubFilesRepository : FilesRepository {
    override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<FilesPage> =
        error("Unexpected continuation")
    override suspend fun loadMoveDestinations(
        folderId: FilesItemId,
        cursor: FilesCursor?,
    ): PutioResult<FilesPage> =
        error("Unexpected destination listing")
    override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort): PutioResult<Unit> =
        error("Unexpected sort")
    override suspend fun rename(itemId: FilesItemId, name: String): PutioResult<Unit> =
        error("Unexpected rename")
    override suspend fun delete(itemId: FilesItemId, mode: FilesDeleteMode): PutioResult<FileDeleteResult> =
        error("Unexpected delete")
    override suspend fun move(
        itemId: FilesItemId,
        destinationId: FilesItemId,
    ): PutioResult<List<FileMoveError>> =
        error("Unexpected move")
    override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> =
        error("Unexpected item resolution")
    override suspend fun startCopy(
        itemId: FilesItemId,
        destinationId: FilesItemId,
    ): PutioResult<FilesCopyId> =
        error("Unexpected copy")
    override suspend fun checkCopy(copyId: FilesCopyId): PutioResult<FilesCopyProgress> =
        error("Unexpected copy check")
}
