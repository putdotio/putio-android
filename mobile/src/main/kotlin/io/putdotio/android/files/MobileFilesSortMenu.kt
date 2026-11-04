package io.putdotio.android.files

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.putdotio.android.R
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.MobileIconButton

internal const val MOBILE_FILES_SORT_TAG = "mobile-files-sort"

@StringRes
internal fun FilesSortKey.label(): Int =
    when (this) {
        FilesSortKey.NAME -> R.string.mobile_files_sort_field_name
        FilesSortKey.SIZE -> R.string.mobile_files_sort_field_size
        FilesSortKey.DATE_ADDED -> R.string.mobile_files_sort_field_date_added
        FilesSortKey.DATE_MODIFIED -> R.string.mobile_files_sort_field_date_modified
        FilesSortKey.TYPE -> R.string.mobile_files_sort_field_type
    }

@StringRes
internal fun FilesSortKey.orderLabel(ascending: Boolean): Int =
    when (this) {
        FilesSortKey.NAME, FilesSortKey.TYPE ->
            if (ascending) R.string.mobile_files_sort_a_to_z else R.string.mobile_files_sort_z_to_a
        FilesSortKey.SIZE ->
            if (ascending) R.string.mobile_files_sort_smallest_first else R.string.mobile_files_sort_largest_first
        FilesSortKey.DATE_ADDED, FilesSortKey.DATE_MODIFIED ->
            if (ascending) R.string.mobile_files_sort_oldest_first else R.string.mobile_files_sort_newest_first
    }

/**
 * The sort button and its menu in three labelled groups, with a check on the current choice: Sort
 * by, Order and Videos. The current choice is the folder's own sort, else the account's default,
 * which the button's state then names; with neither known, it names none.
 */
@Composable
internal fun MobileFilesSortMenu(
    folder: FilesFolderState,
    onSelect: (FilesSort) -> Unit,
    accountSort: FilesSort? = null,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    /** The overflow button sits right of sort; the menu's end then lines up with it. */
    besideNewFolder: Boolean = false,
) {
    var expanded by remember(folder.folder.id.value) { mutableStateOf(false) }
    val current = folder.folder.sort ?: accountSort
    val sortLabel = stringResource(R.string.mobile_files_sort)
    val currentLabel = current?.labelResource()?.let { stringResource(it) }
    // A loading or failed folder keeps the button in place but has no listing to order yet.
    val enabled = folder.operation.canStartOperation && folder.content is FilesContent.Ready

    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }
    // The current sort stays choosable: after a failed reload it retries the order on screen.
    val choose = { sort: FilesSort ->
        expanded = false
        onSelect(sort)
    }

    Box {
        MobileIconButton(
            sortLabel,
            R.drawable.ic_ph_sort_ascending,
            onClick = { expanded = true },
            modifier = Modifier.testTag(MOBILE_FILES_SORT_TAG).sortState(currentLabel),
            enabled = enabled,
            colors = colors,
        )
        MobileSortMenu(
            expanded = expanded && enabled,
            current = current,
            onSelect = choose,
            onDismiss = { expanded = false },
            offset = DpOffset(x = if (besideNewFolder) SORT_MENU_END_SHIFT else 0.dp, y = 0.dp),
        )
    }
}

@Composable
internal fun MobileSortMenu(
    expanded: Boolean,
    current: FilesSort?,
    onSelect: (FilesSort) -> Unit,
    onDismiss: () -> Unit,
    offset: DpOffset = DpOffset.Zero,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        shape = RoundedCornerShape(PutioRadius.large),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.width(SORT_MENU_WIDTH),
    ) {
        val field = current?.field
        val ascending = current?.ascending ?: true
        SortGroupLabel(R.string.mobile_files_sort_by)
        SortGroup {
            FilesSortKey.entries.forEach { option ->
                SortOption(stringResource(option.label()), selected = option == field) {
                    onSelect(current.withField(option))
                }
            }
        }
        SortDivider()
        SortGroupLabel(R.string.mobile_files_sort_order)
        SortGroup {
            val orderField = current.orderField
            listOf(true, false).forEach { direction ->
                SortOption(
                    stringResource(orderField.orderLabel(direction)),
                    selected = field != null && direction == ascending,
                ) { onSelect(orderField.sort(direction)) }
            }
        }
        SortDivider()
        SortGroupLabel(R.string.mobile_files_sort_videos)
        SortGroup {
            FILES_WATCH_SORTS.forEach { sort ->
                SortOption(stringResource(sort.labelResource()), selected = sort == current) { onSelect(sort) }
            }
        }
    }
}

private fun Modifier.sortState(label: String?): Modifier =
    if (label == null) this else semantics { stateDescription = label }

@Composable
private fun SortGroup(content: @Composable () -> Unit) {
    Column(Modifier.selectableGroup()) { content() }
}

@Composable
private fun SortGroupLabel(@StringRes label: Int) {
    Text(
        text = stringResource(label),
        style = PutioTypeScale.labelMd,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SortDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SortOption(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = PutioTypeScale.bodyMd) },
        onClick = onClick,
        leadingIcon = {
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_check),
                    contentDescription = null,
                    modifier = Modifier.size(SORT_CHECK_SIZE),
                )
            } else {
                Spacer(Modifier.size(SORT_CHECK_SIZE))
            }
        },
        colors = MenuDefaults.itemColors(
            textColor = MaterialTheme.colorScheme.onSurface,
            leadingIconColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            },
    )
}

@StringRes
internal fun FilesSort.labelResource(): Int =
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

private val SORT_MENU_WIDTH = 240.dp
private val SORT_MENU_END_SHIFT = 48.dp
private val SORT_CHECK_SIZE = 20.dp
