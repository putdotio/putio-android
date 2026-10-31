package io.putdotio.android.files

import io.putdotio.android.PutioResult
import io.putdotio.android.putioRequest
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.FilesListQuery
import io.putdotio.sdk.files.FilesListResponse

public fun interface FilesFolderCounts {
    public suspend fun itemCount(folderId: FilesItemId): PutioResult<Int?>
}

public class SdkFilesFolderCounts internal constructor(
    private val listFolder: suspend (Long, FilesListQuery) -> FilesListResponse,
) : FilesFolderCounts {
    public constructor(client: PutioClient) : this(
        listFolder = { folderId, query -> client.files.list(parentId = folderId, query = query) },
    )

    override suspend fun itemCount(folderId: FilesItemId): PutioResult<Int?> = putioRequest {
        require(folderId.value >= 0L) { "A folder count needs root or a positive folder ID" }
        listFolder(folderId.value, FilesListQuery(perPage = 1, total = true)).total?.takeIf { it >= 0 }
    }
}
