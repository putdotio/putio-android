package io.putdotio.android.tv.files

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.files.FilesSort

/**
 * Centred M3 choice dialog per the TV contract: 28dp corner, one radio row per
 * sort, focus lands on the current choice, Back dismisses without a change.
 */
@Composable
internal fun TvSortDialog(
    selected: FilesSort?,
    onSelect: (FilesSort) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        // Requested from inside the dialog window, after its content has attached.
        LaunchedEffect(Unit) { selectedFocus.requestFocus() }
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp))
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
                .focusGroup(),
        ) {
            Text(
                text = stringResource(R.string.tv_files_sort_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            FilesSort.entries.forEachIndexed { index, sort ->
                val isSelected = sort == selected
                val focusTarget = isSelected || (selected == null && index == 0)
                ListItem(
                    selected = isSelected,
                    onClick = { onSelect(sort) },
                    headlineContent = { Text(stringResource(sort.tvLabel())) },
                    leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (focusTarget) Modifier.focusRequester(selectedFocus) else Modifier)
                        .semantics {
                            role = Role.RadioButton
                            this.selected = isSelected
                        },
                )
            }
        }
    }
}
