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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.neutralTextButtonColors

internal const val MOBILE_CHOICE_SAVE_TAG = "mobile-choice-save"

/**
 * A choice: the title, an optional line on what it changes, radio rows with an optional description
 * each, then Cancel and a filled Save. Picking a row only marks it; Save applies it. [choices] is null
 * while they load, and [placeholder] shows in their place. A choice's `toString` identifies it.
 */
@Composable
internal fun <T> MobileChoiceDialog(
    title: String,
    message: String?,
    choices: List<T>?,
    label: @Composable (T) -> String,
    description: @Composable (T) -> String?,
    selected: T,
    onSave: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = {},
) {
    // The viewer's pick survives recreation even while the choices load again, by identity rather than
    // position, since a reload may reorder them. Until there is one, the saved value is marked.
    var picked by rememberSaveable(selected) { mutableStateOf<String?>(null) }
    val pending = choices?.indexOfFirst { it.toString() == (picked ?: selected.toString()) } ?: -1
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
                        choices.forEachIndexed { index, choice ->
                            MobileChoiceRow(
                                label = label(choice),
                                description = description(choice),
                                selected = index == pending,
                                onClick = { picked = choice.toString() },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { choices?.getOrNull(pending)?.let(onSave) ?: onDismiss() },
                enabled = choices != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.testTag(MOBILE_CHOICE_SAVE_TAG),
            ) { Text(stringResource(R.string.mobile_files_save)) }
        },
        dismissButton = {
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
