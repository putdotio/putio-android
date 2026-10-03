package io.putdotio.android.trash

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType

data class TrashItem(
    val id: FilesItemId,
    val parentId: FilesItemId?,
    val name: String,
    val type: PutioFileType,
    val sizeBytes: Long,
    val deletedAt: String? = null,
    val expirationDate: String? = null,
) {
    val isFolder: Boolean get() = type == PutioFileType.FOLDER
}

data class TrashPage(
    val items: List<TrashItem>,
    val nextCursor: FilesCursor?,
    val total: Int? = null,
    val trashSizeBytes: Long? = null,
)
