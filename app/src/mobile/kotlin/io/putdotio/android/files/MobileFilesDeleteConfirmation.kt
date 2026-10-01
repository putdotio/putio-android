package io.putdotio.android.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.R

@Composable
internal fun MobileFilesDeleteConfirmation(
    item: FilesItem,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mobile_files_delete)) },
        text = {
            Text(
                text = stringResource(R.string.mobile_files_delete_confirmation, item.name),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(
                    stringResource(R.string.mobile_files_confirm_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mobile_action_cancel)) }
        },
    )
}

/**
 * A folder put.io would not send to Trash stays in Files with web's explanation and a
 * Delete permanently choice; only the confirmation in its dialog sends the permanent delete.
 */
@Composable
internal fun MobileFilesTrashLimitStatus(
    outcome: FilesDeleteOutcome,
    folder: FilesFolderState,
    onEvent: (FilesBrowserEvent) -> Unit,
) {
    var confirming by rememberSaveable(outcome.requestId.value) { mutableStateOf(false) }
    // A refresh that no longer lists the folder leaves nothing for the reducer to delete; a
    // rename keeps the outcome, so the dialog names the folder as it is listed now.
    val listed = folder.content.items().firstOrNull { it.id == outcome.intent.itemId }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.mobile_files_trash_limit_title),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (listed != null) {
            TextButton(onClick = { confirming = true }) {
                Text(stringResource(R.string.mobile_files_trash_limit_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirming && listed != null) {
        val delete = FilesBrowserEvent.Delete(folder.folder.id, listed.id, FilesDeleteMode.PERMANENT)
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.mobile_files_trash_limit_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.mobile_files_trash_limit_message))
                    Text(listed.name, style = MaterialTheme.typography.titleSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onEvent(delete)
                }) {
                    Text(stringResource(R.string.mobile_files_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.mobile_action_cancel)) }
            },
        )
    }
}
