package io.putdotio.android.files

public enum class FilesSortKey {
    NAME,
    SIZE,
    DATE_ADDED,
    DATE_MODIFIED,
    TYPE,
    ;

    public fun sort(ascending: Boolean): FilesSort =
        when (this) {
            NAME -> if (ascending) FilesSort.NAME_ASCENDING else FilesSort.NAME_DESCENDING
            SIZE -> if (ascending) FilesSort.SIZE_ASCENDING else FilesSort.SIZE_DESCENDING
            DATE_ADDED -> if (ascending) FilesSort.DATE_ADDED_ASCENDING else FilesSort.DATE_ADDED_DESCENDING
            DATE_MODIFIED -> if (ascending) FilesSort.DATE_MODIFIED_ASCENDING else FilesSort.DATE_MODIFIED_DESCENDING
            TYPE -> if (ascending) FilesSort.TYPE_ASCENDING else FilesSort.TYPE_DESCENDING
        }
}

public val FilesSort.field: FilesSortKey?
    get() = when (this) {
        FilesSort.NAME_ASCENDING, FilesSort.NAME_DESCENDING -> FilesSortKey.NAME
        FilesSort.SIZE_ASCENDING, FilesSort.SIZE_DESCENDING -> FilesSortKey.SIZE
        FilesSort.DATE_ADDED_ASCENDING, FilesSort.DATE_ADDED_DESCENDING -> FilesSortKey.DATE_ADDED
        FilesSort.DATE_MODIFIED_ASCENDING, FilesSort.DATE_MODIFIED_DESCENDING -> FilesSortKey.DATE_MODIFIED
        FilesSort.TYPE_ASCENDING, FilesSort.TYPE_DESCENDING -> FilesSortKey.TYPE
        FilesSort.WATCH_STATUS_ASCENDING, FilesSort.WATCH_STATUS_DESCENDING -> null
    }

public val FilesSort.ascending: Boolean
    get() = name.endsWith("_ASCENDING")

public val FILES_WATCH_SORTS: List<FilesSort> =
    listOf(FilesSort.WATCH_STATUS_ASCENDING, FilesSort.WATCH_STATUS_DESCENDING)

public fun FilesSort?.withField(field: FilesSortKey): FilesSort =
    field.sort(ascending = this?.field == null || this.ascending)

public val FilesSort?.orderField: FilesSortKey
    get() = this?.field ?: FilesSortKey.NAME
