package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType

@JvmInline
value class FilesItemId(
    val value: Long,
)

@JvmInline
value class FilesCursor(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A Files cursor cannot be blank" }
    }
}

data class FilesFolder(
    val id: FilesItemId,
    val name: String?,
) {
    companion object {
        val Root = FilesFolder(id = FilesItemId(0L), name = null)
    }
}

data class FilesItem(
    val id: FilesItemId,
    val parentId: FilesItemId?,
    val name: String,
    val type: PutioFileType,
    val sizeBytes: Long,
    val createdAt: String,
) {
    val isFolder: Boolean
        get() = type == PutioFileType.FOLDER
}

data class FilesPage(
    val items: List<FilesItem>,
    val nextCursor: FilesCursor?,
)
