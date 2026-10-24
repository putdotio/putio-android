package io.putdotio.android.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale

/** One radio row of a choice dialog: a label and its one-line description. */
internal data class MobileChoice<T>(val value: T, val label: String, val description: String)

/**
 * A choice: the title, one line on what it changes, radio rows with a description each, then
 * Cancel and a filled Save. Picking a row only marks it; Save applies it.
 */
@Composable
internal fun <T> MobileChoiceDialog(
    title: String,
    message: String,
    choices: List<MobileChoice<T>>,
    selected: T,
    onSave: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by rememberSaveable(selected) { mutableStateOf(choices.indexOfFirst { it.value == selected }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Full width less 24dp margins, as wide as a description line needs, up to Material's 560dp.
        modifier = Modifier.widthIn(max = CHOICE_DIALOG_MAX_WIDTH).fillMaxWidth().padding(horizontal = 24.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(message, modifier = Modifier.padding(bottom = 12.dp))
                Column(Modifier.selectableGroup()) {
                    choices.forEachIndexed { index, choice ->
                        MobileChoiceRow(choice, selected = index == pending, onClick = { pending = index })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { choices.getOrNull(pending)?.let { onSave(it.value) } ?: onDismiss() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text(stringResource(R.string.mobile_files_save)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) { Text(stringResource(R.string.mobile_action_cancel)) }
        },
    )
}

@Composable
private fun <T> MobileChoiceRow(choice: MobileChoice<T>, selected: Boolean, onClick: () -> Unit) {
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
            Text(choice.label, style = PutioTypeScale.bodyMd, color = MaterialTheme.colorScheme.onSurface)
            Text(
                choice.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Material's widest dialog, plus the 24dp margins either side.
private val CHOICE_DIALOG_MAX_WIDTH = 608.dp
