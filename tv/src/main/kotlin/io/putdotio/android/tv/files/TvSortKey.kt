package io.putdotio.android.tv.files

import androidx.annotation.StringRes
import io.putdotio.android.R
import io.putdotio.android.files.FilesSortKey

@StringRes
internal fun FilesSortKey.tvLabel(): Int =
    when (this) {
        FilesSortKey.NAME -> R.string.tv_files_sort_field_name
        FilesSortKey.SIZE -> R.string.tv_files_sort_field_size
        FilesSortKey.DATE_ADDED -> R.string.tv_files_sort_field_date_added
        FilesSortKey.DATE_MODIFIED -> R.string.tv_files_sort_field_date_modified
        FilesSortKey.TYPE -> R.string.tv_files_sort_field_type
    }

@StringRes
internal fun FilesSortKey.orderLabel(ascending: Boolean): Int =
    when (this) {
        FilesSortKey.NAME, FilesSortKey.TYPE ->
            if (ascending) R.string.tv_files_sort_a_to_z else R.string.tv_files_sort_z_to_a
        FilesSortKey.SIZE ->
            if (ascending) R.string.tv_files_sort_smallest_first else R.string.tv_files_sort_largest_first
        FilesSortKey.DATE_ADDED, FilesSortKey.DATE_MODIFIED ->
            if (ascending) R.string.tv_files_sort_oldest_first else R.string.tv_files_sort_newest_first
    }
