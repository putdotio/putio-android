package io.putdotio.android

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.putdotio.android.files.FilesItem

@Composable
internal fun MobileFilesDeleteConfirmation(
    item: FilesItem,
    trash: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (trash) R.string.mobile_files_trash else R.string.mobile_files_delete)) },
        text = {
            Text(
                text = stringResource(
                    if (trash) R.string.mobile_files_trash_confirmation else R.string.mobile_files_delete_confirmation,
                    item.name,
                ),
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
