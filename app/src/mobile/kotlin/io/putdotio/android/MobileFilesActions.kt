package io.putdotio.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRenameCompletion

internal const val MOBILE_FILES_RENAME_FIELD_TAG = "mobile-files-rename-field"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileFilesActions(
    item: FilesItem,
    folderId: FilesItemId,
    operation: FilesFolderOperation,
    renameCompletion: FilesRenameCompletion?,
    onEvent: (FilesBrowserEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val failed = operation as? FilesFolderOperation.Failed
    val failedRename = (failed?.intent as? FilesFolderOperationIntent.Rename)?.takeIf { it.itemId == item.id }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(failedRename?.name ?: item.name) }
    var submittedName by rememberSaveable { mutableStateOf<String?>(null) }
    var previousCompletionRequestValue by rememberSaveable { mutableStateOf<Long?>(null) }
    val dismiss = {
        if (failedRename != null) onEvent(FilesBrowserEvent.AbandonRename(folderId, failedRename))
        onDismiss()
    }
    val pending = operation is FilesFolderOperation.Loading
    val reloadStarted = when (operation) {
        is FilesFolderOperation.Loading -> operation.intent is FilesFolderOperationIntent.Rename &&
            operation.phase == FilesFolderOperationPhase.RELOADING
        is FilesFolderOperation.Failed -> operation.intent is FilesFolderOperationIntent.Rename &&
            operation.phase == FilesFolderOperationPhase.RELOADING
        FilesFolderOperation.Idle -> false
    }
    LaunchedEffect(renameCompletion, submittedName, previousCompletionRequestValue) {
        // Keep the confirmed mutation across frames even when save and reload finish together.
        val submitted = submittedName
        val matchesSubmission = submitted != null &&
            renameCompletion?.intent == FilesFolderOperationIntent.Rename(item.id, submitted)
        if (matchesSubmission && renameCompletion.requestId.value != previousCompletionRequestValue) onDismiss()
    }
    if (editing) {
        val focusRequester = remember { FocusRequester() }
        val submit = {
            if (!pending && !reloadStarted) {
                if (draft == item.name) {
                    dismiss()
                } else {
                    submittedName = draft
                    previousCompletionRequestValue = renameCompletion?.requestId?.value
                    onEvent(FilesBrowserEvent.Rename(folderId, item.id, draft))
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!pending) dismiss() },
            title = { Text(stringResource(R.string.mobile_files_rename)) },
            text = {
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        enabled = !pending,
                        label = { Text(stringResource(R.string.mobile_files_name)) },
                        isError = failedRename != null && failed.phase == FilesFolderOperationPhase.RENAMING,
                        supportingText = {
                            if (failedRename != null && failed.phase == FilesFolderOperationPhase.RENAMING) {
                                Text(
                                    text = stringResource(failed.failure.mobileMessageResource()),
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag(MOBILE_FILES_RENAME_FIELD_TAG),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = submit, enabled = !pending && !reloadStarted) {
                    Text(stringResource(if (pending) R.string.mobile_files_renaming else R.string.mobile_files_save))
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, enabled = !pending) {
                    Text(stringResource(R.string.mobile_action_cancel))
                }
            },
        )
    } else {
        ModalBottomSheet(onDismissRequest = dismiss) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.mobile_files_rename)) },
                modifier = Modifier
                    .clickable(enabled = !pending && !reloadStarted) { editing = true }
                    .padding(bottom = 24.dp),
            )
        }
    }
}
