package io.putdotio.android.tv.files

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.putdotio.android.FilesFailure
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.apiReason
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

/** put.io's reason for a refused request, else this failure's copy. */
@Composable
internal fun FilesFailure.tvMessageText(): String = (this as? PutioFailure)?.apiReason ?: stringResource(tvMessage())

@StringRes
internal fun FilesFailure.tvMessage(): Int =
    when (this) {
        is PutioFailure.AuthenticationRequired -> R.string.tv_error_session
        is PutioFailure.AccessDenied -> R.string.tv_error_forbidden
        is PutioFailure.RateLimited -> R.string.tv_error_rate_limited
        is PutioFailure.NetworkUnavailable -> R.string.tv_error_network
        FilesFailure.NavigationBlocked -> R.string.tv_error_navigation_blocked
        is PutioFailure.ServerUnavailable,
        is PutioFailure.ApiRejected,
        is PutioFailure.InvalidResponse,
        is PutioFailure.Misconfigured,
        is PutioFailure.Unexpected,
        -> R.string.tv_error_unavailable
    }
