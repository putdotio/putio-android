package io.putdotio.android.tv.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.putdotio.android.R
import io.putdotio.android.files.FilesSort
import io.putdotio.android.tv.TvChoice
import io.putdotio.android.tv.TvChoiceDialog

/** The sort picker: one row per [FilesSort], focus on the folder's current sort. */
@Composable
internal fun TvSortDialog(
    selected: FilesSort?,
    onSelect: (FilesSort) -> Unit,
    onDismiss: () -> Unit,
) {
    TvChoiceDialog(
        title = stringResource(R.string.tv_files_sort_title),
        choices = FilesSort.entries.map { TvChoice(it, stringResource(it.tvLabel())) },
        selected = selected,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}
