package io.putdotio.android.tv.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import io.putdotio.android.R
import io.putdotio.android.files.FilesSort
import io.putdotio.android.tv.TvSheetLabel
import io.putdotio.android.tv.TvSheetOption
import io.putdotio.android.tv.TvSideSheet
import io.putdotio.android.tv.TvSheetRows
import io.putdotio.android.tv.focusRequesterIf

@Composable
internal fun TvSortSheet(
    selected: FilesSort?,
    onSelect: (FilesSort) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester?,
) {
    val field = selected?.tvField
    val ascending = selected?.ascending ?: true
    val watchSorts = listOf(FilesSort.WATCH_STATUS_ASCENDING, FilesSort.WATCH_STATUS_DESCENDING)
    TvSideSheet(
        onDismiss = onDismiss,
        returnFocus = returnFocus,
        title = stringResource(R.string.tv_files_sort_title),
    ) { initialFocus ->
        TvSheetRows {
            TvSheetLabel(stringResource(R.string.tv_files_sort_by))
            TvSortField.entries.forEach { option ->
                TvSheetOption(
                    label = stringResource(option.label),
                    detail = null,
                    selected = option == field,
                    onClick = { onSelect(option.sort(ascending = if (field == null) true else ascending)) },
                    modifier = Modifier.focusRequesterIf(
                        option == field || (selected == null && option == TvSortField.NAME),
                        initialFocus,
                    ),
                )
            }
            TvSheetLabel(stringResource(R.string.tv_files_sort_order))
            val orderField = field ?: TvSortField.NAME
            listOf(true, false).forEach { direction ->
                TvSheetOption(
                    label = stringResource(orderField.orderLabel(direction)),
                    detail = null,
                    selected = field != null && direction == ascending,
                    onClick = { onSelect(orderField.sort(direction)) },
                )
            }
            TvSheetLabel(stringResource(R.string.tv_files_sort_videos))
            watchSorts.forEach { sort ->
                TvSheetOption(
                    label = stringResource(sort.tvLabel()),
                    detail = null,
                    selected = sort == selected,
                    onClick = { onSelect(sort) },
                    modifier = Modifier.focusRequesterIf(sort == selected, initialFocus),
                )
            }
        }
    }
}
