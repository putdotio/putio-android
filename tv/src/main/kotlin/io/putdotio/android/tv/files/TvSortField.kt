package io.putdotio.android.tv.files

import androidx.annotation.StringRes
import io.putdotio.android.R
import io.putdotio.android.files.FilesSort

internal enum class TvSortField(@param:StringRes val label: Int) {
    NAME(R.string.tv_files_sort_field_name),
    SIZE(R.string.tv_files_sort_field_size),
    DATE_ADDED(R.string.tv_files_sort_field_date_added),
    DATE_MODIFIED(R.string.tv_files_sort_field_date_modified),
    TYPE(R.string.tv_files_sort_field_type),
}

internal val FilesSort.tvField: TvSortField?
    get() = when (this) {
        FilesSort.NAME_ASCENDING, FilesSort.NAME_DESCENDING -> TvSortField.NAME
        FilesSort.SIZE_ASCENDING, FilesSort.SIZE_DESCENDING -> TvSortField.SIZE
        FilesSort.DATE_ADDED_ASCENDING, FilesSort.DATE_ADDED_DESCENDING -> TvSortField.DATE_ADDED
        FilesSort.DATE_MODIFIED_ASCENDING, FilesSort.DATE_MODIFIED_DESCENDING -> TvSortField.DATE_MODIFIED
        FilesSort.TYPE_ASCENDING, FilesSort.TYPE_DESCENDING -> TvSortField.TYPE
        FilesSort.WATCH_STATUS_ASCENDING, FilesSort.WATCH_STATUS_DESCENDING -> null
    }

internal val FilesSort.ascending: Boolean
    get() = name.endsWith("_ASCENDING")

internal fun TvSortField.sort(ascending: Boolean): FilesSort =
    when (this) {
        TvSortField.NAME -> if (ascending) FilesSort.NAME_ASCENDING else FilesSort.NAME_DESCENDING
        TvSortField.SIZE -> if (ascending) FilesSort.SIZE_ASCENDING else FilesSort.SIZE_DESCENDING
        TvSortField.DATE_ADDED -> if (ascending) FilesSort.DATE_ADDED_ASCENDING else FilesSort.DATE_ADDED_DESCENDING
        TvSortField.DATE_MODIFIED ->
            if (ascending) FilesSort.DATE_MODIFIED_ASCENDING else FilesSort.DATE_MODIFIED_DESCENDING
        TvSortField.TYPE -> if (ascending) FilesSort.TYPE_ASCENDING else FilesSort.TYPE_DESCENDING
    }

@StringRes
internal fun TvSortField.orderLabel(ascending: Boolean): Int =
    when (this) {
        TvSortField.NAME, TvSortField.TYPE ->
            if (ascending) R.string.tv_files_sort_a_to_z else R.string.tv_files_sort_z_to_a
        TvSortField.SIZE ->
            if (ascending) R.string.tv_files_sort_smallest_first else R.string.tv_files_sort_largest_first
        TvSortField.DATE_ADDED, TvSortField.DATE_MODIFIED ->
            if (ascending) R.string.tv_files_sort_oldest_first else R.string.tv_files_sort_newest_first
    }
