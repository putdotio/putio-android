package io.putdotio.android.files

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
    val apiValue: String,
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
        fun fromApiValue(value: String?): FilesSort? =
            entries.firstOrNull { it.apiValue == value }
    }
}
