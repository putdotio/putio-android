package io.putdotio.android.files

import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FileMoveError

internal abstract class StubFilesRepository : FilesRepository {
    override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<FilesPage> =
        error("Unexpected continuation")
    override suspend fun loadMoveDestinations(
        folderId: FilesItemId,
        cursor: FilesCursor?,
    ): FilesRepositoryResult<FilesPage> =
        error("Unexpected destination listing")
    override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort): FilesRepositoryResult<Unit> =
        error("Unexpected sort")
    override suspend fun rename(itemId: FilesItemId, name: String): FilesRepositoryResult<Unit> =
        error("Unexpected rename")
    override suspend fun delete(itemId: FilesItemId, mode: FilesDeleteMode): FilesRepositoryResult<FileDeleteResult> =
        error("Unexpected delete")
    override suspend fun move(
        itemId: FilesItemId,
        destinationId: FilesItemId,
    ): FilesRepositoryResult<List<FileMoveError>> =
        error("Unexpected move")
    override suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem> =
        error("Unexpected item resolution")
}
