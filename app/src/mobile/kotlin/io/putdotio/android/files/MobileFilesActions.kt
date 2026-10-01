package io.putdotio.android.files

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.R
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.description

internal const val MOBILE_FILES_RENAME_FIELD_TAG = "mobile-files-rename-field"
internal const val MOBILE_FILES_DOWNLOAD_ACTION_TAG = "mobile-files-download-action"
internal const val MOBILE_FILES_SHARE_ACTION_TAG = "mobile-files-share-action"

/**
 * Whether the row's sheet offers anything. Download and Share read the original, so a friend's
 * shared file keeps them; a shared folder, including the shared root, offers nothing.
 */
internal fun FilesItem.hasMobileActions(canDownload: Boolean, canShare: Boolean): Boolean =
    id.value > 0L && (acceptsOwnerActions || (canDownload && isPlayable) || (canShare && !isFolder))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileFilesActions(
    item: FilesItem,
    folderId: FilesItemId,
    operation: FilesFolderOperation,
    renameCompletion: FilesRenameCompletion?,
    onEvent: (FilesBrowserEvent) -> Unit,
    onDismiss: () -> Unit,
    confirmedTrashEnabled: Boolean? = null,
    onMoveItem: ((FilesItem) -> Unit)? = null,
    downloadStatus: DownloadStatus? = null,
    onDownloadItem: ((FilesItem) -> Unit)? = null,
    onShareItem: ((FilesItem) -> Unit)? = null,
) {
    val failed = operation as? FilesFolderOperation.Failed
    val failedRename = (failed?.intent as? FilesFolderOperationIntent.Rename)?.takeIf { it.itemId == item.id }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(failedRename?.name ?: item.name) }
    var submittedName by rememberSaveable { mutableStateOf<String?>(null) }
    var previousCompletionRequestValue by rememberSaveable { mutableStateOf<Long?>(null) }
    // Only permanent deletion confirms; Trash keeps the item, as on web and iOS.
    var confirmingPermanentDelete by rememberSaveable { mutableStateOf(false) }
    var deleteSubmitted by rememberSaveable { mutableStateOf(false) }
    // A Move to trash tap is sent after the next composition, against the latest Trash setting:
    // a tap made before a change to the setting reached the sheet must not send TRASH, which
    // the server applies as permanent deletion once Trash is off.
    var trashTapped by remember { mutableStateOf(false) }
    val currentTrashEnabled by rememberUpdatedState(confirmedTrashEnabled)
    val currentOperation by rememberUpdatedState(operation)
    LaunchedEffect(confirmedTrashEnabled) {
        if (confirmedTrashEnabled != false) confirmingPermanentDelete = false
    }
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
    val submitDelete = { mode: FilesDeleteMode ->
        val confirmedMode = when (currentTrashEnabled) {
            true -> FilesDeleteMode.TRASH
            false -> FilesDeleteMode.PERMANENT
            null -> null
        }
        if (!deleteSubmitted && confirmedMode == mode && currentOperation.canStartOperation) {
            deleteSubmitted = true
            onEvent(FilesBrowserEvent.Delete(folderId, item.id, mode))
            onDismiss()
        }
    }
    if (trashTapped) {
        SideEffect {
            trashTapped = false
            submitDelete(FilesDeleteMode.TRASH)
        }
    }
    if (confirmingPermanentDelete && confirmedTrashEnabled == false) {
        MobileFilesDeleteConfirmation(
            item = item,
            enabled = !deleteSubmitted && operation.canStartOperation,
            onDismiss = {
                confirmingPermanentDelete = false
                dismiss()
            },
            onConfirm = { submitDelete(FilesDeleteMode.PERMANENT) },
        )
    } else if (editing) {
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
                                    text = failed.failure.mobileMessage(),
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
        ModalBottomSheet(
            onDismissRequest = dismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            // Delete carries the sheet's bottom inset; without it the last row keeps the same gap.
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .then(if (item.acceptsOwnerActions) Modifier else Modifier.padding(bottom = 24.dp)),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                if (item.acceptsOwnerActions) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.mobile_files_rename)) },
                        modifier = Modifier
                            .clickable(enabled = operation.canStartOperation, role = Role.Button) { editing = true },
                    )
                }
                if (onDownloadItem != null && item.isPlayable) {
                    // A completed or running download shows its state; the row stays informational.
                    val downloadable = downloadStatus == null || downloadStatus is DownloadStatus.Failed
                    ListItem(
                        headlineContent = {
                            Text(stringResource(
                                when (downloadStatus) {
                                    null, is DownloadStatus.Failed -> R.string.mobile_files_download
                                    is DownloadStatus.Completed -> R.string.mobile_files_downloaded
                                    else -> R.string.mobile_files_downloading
                                },
                            ))
                        },
                        supportingContent = (downloadStatus as? DownloadStatus.Failed)?.let {
                            { Text(it.description(LocalContext.current)) }
                        },
                        modifier = Modifier
                            .testTag(MOBILE_FILES_DOWNLOAD_ACTION_TAG)
                            .clickable(enabled = downloadable && item.id.value > 0L, role = Role.Button) {
                                onDownloadItem(item)
                                dismiss()
                            },
                    )
                }
                if (onShareItem != null && !item.isFolder && item.id.value > 0L) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.mobile_files_share)) },
                        supportingContent = { Text(stringResource(R.string.mobile_files_share_description)) },
                        modifier = Modifier
                            .testTag(MOBILE_FILES_SHARE_ACTION_TAG)
                            .clickable(role = Role.Button) {
                                onShareItem(item)
                                dismiss()
                            },
                    )
                }
                if (onMoveItem != null && item.acceptsOwnerActions) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.mobile_files_move)) },
                        modifier = Modifier.clickable(
                            enabled = item.id.value > 0L && operation.canStartOperation,
                            role = Role.Button,
                        ) {
                            onMoveItem(item)
                            dismiss()
                        },
                    )
                }
                if (item.acceptsOwnerActions) {
                    HorizontalDivider()
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(
                                    if (confirmedTrashEnabled == true) {
                                        R.string.mobile_files_trash
                                    } else {
                                        R.string.mobile_files_delete
                                    },
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        supportingContent = if (confirmedTrashEnabled == null) {
                            { Text(stringResource(R.string.mobile_files_delete_settings_unknown)) }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .clickable(
                                enabled = confirmedTrashEnabled != null &&
                                    item.id.value > 0L &&
                                    operation.canStartOperation,
                                role = Role.Button,
                            ) {
                                when (currentTrashEnabled) {
                                    true -> trashTapped = true
                                    false -> confirmingPermanentDelete = true
                                    null -> Unit
                                }
                            }
                            .padding(bottom = 24.dp),
                    )
                }
            }
        }
    }
}
