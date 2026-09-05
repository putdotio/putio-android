package io.putdotio.android

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.canStartOperation

internal const val MOBILE_FILES_SORT_TAG = "mobile-files-sort"

@Composable
internal fun MobileFilesSortMenu(
    folder: FilesFolderState,
    onSelect: (FilesSort) -> Unit,
) {
    var expanded by remember(folder.folder.id.value) { mutableStateOf(false) }
    val sortLabel = stringResource(R.string.mobile_files_sort)
    val currentLabel = stringResource(folder.folder.sort?.labelResource() ?: R.string.mobile_files_sort_account_default)
    val enabled = folder.operation.canStartOperation

    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }

    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier
                .testTag(MOBILE_FILES_SORT_TAG)
                .semantics { stateDescription = currentLabel },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_sort_ascending),
                contentDescription = sortLabel,
            )
        }
        DropdownMenu(
            expanded = shouldShowFilesSortMenu(expanded = expanded, enabled = enabled),
            onDismissRequest = { expanded = false },
            modifier = Modifier.selectableGroup(),
        ) {
            FilesSort.entries.forEach { sort ->
                val selectedSort = sort == folder.folder.sort
                DropdownMenuItem(
                    text = { Text(stringResource(sort.labelResource())) },
                    enabled = enabled,
                    onClick = {
                        expanded = false
                        onSelect(sort)
                    },
                    leadingIcon = { RadioButton(selected = selectedSort, onClick = null) },
                    modifier = Modifier.semantics {
                        selected = selectedSort
                        role = Role.RadioButton
                    },
                )
            }
        }
    }
}

internal fun shouldShowFilesSortMenu(
    expanded: Boolean,
    enabled: Boolean,
): Boolean = expanded && enabled

@StringRes
private fun FilesSort.labelResource(): Int =
    when (this) {
        FilesSort.NAME_ASCENDING -> R.string.mobile_files_sort_name_ascending
        FilesSort.NAME_DESCENDING -> R.string.mobile_files_sort_name_descending
        FilesSort.SIZE_ASCENDING -> R.string.mobile_files_sort_size_ascending
        FilesSort.SIZE_DESCENDING -> R.string.mobile_files_sort_size_descending
        FilesSort.DATE_ADDED_ASCENDING -> R.string.mobile_files_sort_date_added_ascending
        FilesSort.DATE_ADDED_DESCENDING -> R.string.mobile_files_sort_date_added_descending
        FilesSort.DATE_MODIFIED_ASCENDING -> R.string.mobile_files_sort_date_modified_ascending
        FilesSort.DATE_MODIFIED_DESCENDING -> R.string.mobile_files_sort_date_modified_descending
        FilesSort.TYPE_ASCENDING -> R.string.mobile_files_sort_type_ascending
        FilesSort.TYPE_DESCENDING -> R.string.mobile_files_sort_type_descending
        FilesSort.WATCH_STATUS_ASCENDING -> R.string.mobile_files_sort_watch_ascending
        FilesSort.WATCH_STATUS_DESCENDING -> R.string.mobile_files_sort_watch_descending
    }
