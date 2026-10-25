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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.MobileDialogDismissButton
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioDestructiveButton
import androidx.compose.ui.window.DialogProperties

/**
 * Permanent deletion always confirms: the title names the count and the action, the body the
 * consequence, and the confirm is the red filled button.
 */
@Composable
internal fun MobileFilesDeleteConfirmation(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    count: Int = 1,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp),
        title = { Text(pluralStringResource(R.plurals.mobile_files_delete_permanently_title, count, count)) },
        text = {
            Text(
                text = stringResource(R.string.mobile_files_delete_permanently_message),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            PutioDestructiveButton(onClick = onConfirm, enabled = enabled) {
                Text(stringResource(R.string.mobile_files_delete))
            }
        },
        dismissButton = { MobileDialogDismissButton(onClick = onDismiss) },
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
    // A refresh that no longer lists an item leaves nothing for the reducer to delete; a rename
    // keeps the outcome, so the dialog names the items as they are listed now.
    val listed = folder.content.items().filter { it.id in outcome.intent.itemIds }.takeIf { it.isNotEmpty() }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.mobile_files_trash_limit_title),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (listed != null) {
            TextButton(onClick = { confirming = true }) {
                Text(
                    stringResource(R.string.mobile_files_trash_limit_delete),
                    color = PutioDesignTokens.redTextSecondary,
                )
            }
        }
    }
    if (confirming && listed != null) {
        val delete = FilesBrowserEvent.Delete(folder.folder.id, listed.map { it.id }, FilesDeleteMode.PERMANENT)
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = {
                Text(
                    if (listed.size > 1) {
                        pluralStringResource(R.plurals.mobile_files_delete_permanently_title, listed.size, listed.size)
                    } else {
                        stringResource(R.string.mobile_files_trash_limit_title)
                    },
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(
                            if (listed.size > 1) {
                                R.string.mobile_files_trash_limit_message_many
                            } else {
                                R.string.mobile_files_trash_limit_message
                            },
                        ),
                    )
                    listed.forEach { Text(it.name, style = MaterialTheme.typography.titleSmall) }
                }
            },
            confirmButton = {
                PutioDestructiveButton(onClick = {
                    confirming = false
                    onEvent(delete)
                }) {
                    Text(stringResource(R.string.mobile_files_delete))
                }
            },
            dismissButton = {
                MobileDialogDismissButton(onClick = { confirming = false })
            },
        )
    }
}
