package io.putdotio.android.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.neutralTextButtonColors

/**
 * A choice: the title, an optional line on what it changes, radio rows with an optional description
 * each, then Cancel. Tapping a row applies it and closes; tapping the current one only closes. [choices]
 * is null while they load, and [placeholder] shows in their place.
 */
@Composable
internal fun <T> MobileChoiceDialog(
    title: String,
    message: String?,
    choices: List<T>?,
    label: @Composable (T) -> String,
    description: @Composable (T) -> String?,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Full width less 24dp margins, as wide as a description line needs, up to Material's 560dp.
        modifier = modifier.widthIn(max = CHOICE_DIALOG_MAX_WIDTH).fillMaxWidth().padding(horizontal = 24.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            // Choice counts and font scale vary; keep every option reachable inside the dialog.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                message?.let { Text(it, modifier = Modifier.padding(bottom = 12.dp)) }
                if (choices == null) {
                    placeholder()
                } else {
                    Column(Modifier.selectableGroup()) {
                        choices.forEach { choice ->
                            MobileChoiceRow(
                                label = label(choice),
                                description = description(choice),
                                selected = choice == selected,
                                onClick = { if (choice == selected) onDismiss() else onSelect(choice) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = neutralTextButtonColors(),
            ) { Text(stringResource(R.string.mobile_action_cancel)) }
        },
    )
}

@Composable
private fun MobileChoiceRow(label: String, description: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Column {
            Text(label, style = PutioTypeScale.bodyMd, color = MaterialTheme.colorScheme.onSurface)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// Material's widest dialog, plus the 24dp margins either side.
private val CHOICE_DIALOG_MAX_WIDTH = 608.dp
