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

enum class FilesSort(
    internal val apiValue: String,
) {
    NAME_ASCENDING("NAME_ASC"),
    NAME_DESCENDING("NAME_DESC"),
    SIZE_ASCENDING("SIZE_ASC"),
    SIZE_DESCENDING("SIZE_DESC"),
    DATE_ADDED_ASCENDING("DATE_ASC"),
    DATE_ADDED_DESCENDING("DATE_DESC"),
    DATE_MODIFIED_ASCENDING("MODIFIED_ASC"),
    DATE_MODIFIED_DESCENDING("MODIFIED_DESC"),
    TYPE_ASCENDING("TYPE_ASC"),
    TYPE_DESCENDING("TYPE_DESC"),
    WATCH_STATUS_ASCENDING("WATCH_ASC"),
    WATCH_STATUS_DESCENDING("WATCH_DESC"),
    ;

    companion object {
        internal fun fromApiValue(value: String?): FilesSort? =
            entries.firstOrNull { it.apiValue == value }
    }
}

data class FilesFolder(
    val id: FilesItemId,
    val name: String?,
    val sort: FilesSort? = null,
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
    val sort: FilesSort? = null,
)
