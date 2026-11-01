package io.putdotio.android.files

import io.putdotio.android.PutioResult
import io.putdotio.android.putioRequest
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.FilesListQuery
import io.putdotio.sdk.files.FilesListResponse
import io.putdotio.sdk.files.PutioFileType

public fun interface FilesFolderCounts {
    public suspend fun itemCount(folderId: FilesItemId): PutioResult<Int?>

    public suspend fun folderCount(folderId: FilesItemId): PutioResult<Int?> = PutioResult.Success(null)
}

public class SdkFilesFolderCounts internal constructor(
    private val listFolder: suspend (Long, FilesListQuery) -> FilesListResponse,
) : FilesFolderCounts {
    public constructor(client: PutioClient) : this(
        listFolder = { folderId, query -> client.files.list(parentId = folderId, query = query) },
    )

    override suspend fun itemCount(folderId: FilesItemId): PutioResult<Int?> = total(folderId, fileType = null)

    override suspend fun folderCount(folderId: FilesItemId): PutioResult<Int?> =
        total(folderId, fileType = PutioFileType.FOLDER)

    private suspend fun total(folderId: FilesItemId, fileType: PutioFileType?): PutioResult<Int?> = putioRequest {
        require(folderId.value >= 0L) { "A folder count needs root or a positive folder ID" }
        val query = FilesListQuery(perPage = 1, total = true, fileType = fileType)
        listFolder(folderId.value, query).total?.takeIf { it >= 0 }
    }
}
