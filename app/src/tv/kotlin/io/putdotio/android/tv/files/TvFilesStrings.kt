package io.putdotio.android.tv.files

import androidx.annotation.StringRes
import io.putdotio.android.R
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesSort

@StringRes
internal fun FilesSort.tvLabel(): Int =
    when (this) {
        FilesSort.NAME_ASCENDING -> R.string.tv_files_sort_name_ascending
        FilesSort.NAME_DESCENDING -> R.string.tv_files_sort_name_descending
        FilesSort.SIZE_ASCENDING -> R.string.tv_files_sort_size_ascending
        FilesSort.SIZE_DESCENDING -> R.string.tv_files_sort_size_descending
        FilesSort.DATE_ADDED_ASCENDING -> R.string.tv_files_sort_date_added_ascending
        FilesSort.DATE_ADDED_DESCENDING -> R.string.tv_files_sort_date_added_descending
        FilesSort.DATE_MODIFIED_ASCENDING -> R.string.tv_files_sort_date_modified_ascending
        FilesSort.DATE_MODIFIED_DESCENDING -> R.string.tv_files_sort_date_modified_descending
        FilesSort.TYPE_ASCENDING -> R.string.tv_files_sort_type_ascending
        FilesSort.TYPE_DESCENDING -> R.string.tv_files_sort_type_descending
        FilesSort.WATCH_STATUS_ASCENDING -> R.string.tv_files_sort_watch_ascending
        FilesSort.WATCH_STATUS_DESCENDING -> R.string.tv_files_sort_watch_descending
    }

@StringRes
internal fun FilesFailure.tvMessage(): Int =
    when (this) {
        is FilesFailure.AuthenticationRequired -> R.string.tv_error_session
        is FilesFailure.AccessDenied -> R.string.tv_error_forbidden
        is FilesFailure.RateLimited -> R.string.tv_error_rate_limited
        is FilesFailure.NetworkUnavailable -> R.string.tv_error_network
        FilesFailure.NavigationBlocked,
        is FilesFailure.ServerUnavailable,
        is FilesFailure.ApiRejected,
        is FilesFailure.InvalidResponse,
        is FilesFailure.Misconfigured,
        is FilesFailure.Unexpected,
        -> R.string.tv_error_unavailable
    }
