package io.putdotio.android.tv.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.putdotio.android.R
import io.putdotio.android.files.FILES_WATCH_SORTS
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.FilesSortKey
import io.putdotio.android.files.ascending
import io.putdotio.android.files.field
import io.putdotio.android.files.orderField
import io.putdotio.android.files.withField
import io.putdotio.android.tv.TvSheetLabel
import io.putdotio.android.tv.TvSheetOption
import io.putdotio.android.tv.TvSideSheet
import io.putdotio.android.tv.TvSheetRows
import io.putdotio.android.tv.focusRequesterIf

@Composable
internal fun TvSortSheet(
    title: String,
    selected: FilesSort?,
    onSelect: (FilesSort) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester?,
    scrim: Color? = null,
) {
    val field = selected?.field
    val ascending = selected?.ascending ?: true
    TvSideSheet(
        onDismiss = onDismiss,
        returnFocus = returnFocus,
        title = title,
        scrim = scrim,
    ) { initialFocus ->
        TvSheetRows {
            TvSheetLabel(stringResource(R.string.tv_files_sort_by))
            FilesSortKey.entries.forEach { option ->
                TvSheetOption(
                    label = stringResource(option.tvLabel()),
                    detail = null,
                    selected = option == field,
                    onClick = { onSelect(selected.withField(option)) },
                    modifier = Modifier.focusRequesterIf(
                        option == field || (selected == null && option == FilesSortKey.NAME),
                        initialFocus,
                    ),
                )
            }
            TvSheetLabel(stringResource(R.string.tv_files_sort_order))
            val orderField = selected.orderField
            listOf(true, false).forEach { direction ->
                TvSheetOption(
                    label = stringResource(orderField.orderLabel(direction)),
                    detail = null,
                    selected = field != null && direction == ascending,
                    onClick = { onSelect(orderField.sort(direction)) },
                )
            }
            TvSheetLabel(stringResource(R.string.tv_files_sort_videos))
            FILES_WATCH_SORTS.forEach { sort ->
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

internal val FILES_SHEET_SCRIM = Color.Black.copy(alpha = 0.72f)
