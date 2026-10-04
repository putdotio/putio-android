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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
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
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.description

internal const val MOBILE_FILES_RENAME_FIELD_TAG = "mobile-files-rename-field"
internal const val MOBILE_FILES_DOWNLOAD_ACTION_TAG = "mobile-files-download-action"
internal const val MOBILE_FILES_SHARE_ACTION_TAG = "mobile-files-share-action"
internal const val MOBILE_FILES_COPY_ACTION_TAG = "mobile-files-copy-action"

/**
 * Whether the row's sheet offers anything. Download and Share read the original, and Make a copy
 * reads it into the viewer's own files, so a friend's shared file or folder keeps them; the shared
 * root and each friend's folder offer nothing.
 */
internal fun FilesItem.hasMobileActions(canDownload: Boolean, canShare: Boolean, canCopy: Boolean): Boolean =
    id.value > 0L &&
        (acceptsOwnerActions || (canDownload && isPlayable) || (canShare && !isFolder) || (canCopy && canMakeCopy))

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
    onCopyItem: ((FilesItem) -> Unit)? = null,
    canStartCopy: Boolean = true,
) {
    val failed = operation as? FilesFolderOperation.Failed
    val failedRename = (failed?.intent as? FilesFolderOperationIntent.Rename)?.takeIf { it.itemId == item.id }
    var editing by rememberSaveable { mutableStateOf(false) }
    val draft = rememberSaveable { mutableStateOf(failedRename?.name ?: item.name) }
    var submittedName by rememberSaveable { mutableStateOf<String?>(null) }
    var previousCompletionRequestValue by rememberSaveable { mutableStateOf<Long?>(null) }
    // Only permanent deletion confirms; Trash keeps the item, as on web and iOS.
    var confirmingPermanentDelete by rememberSaveable { mutableStateOf(false) }
    var deleteSubmitted by rememberSaveable { mutableStateOf(false) }
    // A Move to trash tap is sent after the next composition, against the latest Trash setting:
    // a tap made before a change to the setting reached the sheet must not send TRASH, which
    // the server applies as permanent deletion once Trash is off.
    var trashTapped by remember { mutableStateOf(false) }
    val currentTrashEnabled = rememberUpdatedState(confirmedTrashEnabled)
    val currentOperation by rememberUpdatedState(operation)
    LaunchedEffect(confirmedTrashEnabled) {
        if (confirmedTrashEnabled != false) confirmingPermanentDelete = false
    }
    val dismiss = {
        if (failedRename != null) onEvent(FilesBrowserEvent.AbandonRename(folderId, failedRename))
        onDismiss()
    }
    LaunchedEffect(renameCompletion, submittedName, previousCompletionRequestValue) {
        // Keep the confirmed mutation across frames even when save and reload finish together.
        if (renameCompletion.confirms(item.id, submittedName, previousCompletionRequestValue)) onDismiss()
    }
    val submitDelete = { mode: FilesDeleteMode ->
        if (!deleteSubmitted &&
            confirmedDeleteMode(currentTrashEnabled.value) == mode &&
            currentOperation.canStartOperation
        ) {
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
        MobileFilesRenameDialog(
            item = item,
            operation = operation,
            draft = draft,
            onRename = { name ->
                submittedName = name
                previousCompletionRequestValue = renameCompletion?.requestId?.value
                onEvent(FilesBrowserEvent.Rename(folderId, item.id, name))
            },
            onDismiss = dismiss,
        )
    } else {
        MobileFilesActionsSheet(
            item = item,
            operation = operation,
            confirmedTrashEnabled = confirmedTrashEnabled,
            currentTrashEnabled = currentTrashEnabled,
            downloadStatus = downloadStatus,
            canStartCopy = canStartCopy,
            onRename = { editing = true },
            onTrash = { trashTapped = true },
            onConfirmPermanentDelete = { confirmingPermanentDelete = true },
            onDismiss = dismiss,
            onMoveItem = onMoveItem,
            onDownloadItem = onDownloadItem,
            onShareItem = onShareItem,
            onCopyItem = onCopyItem,
        )
    }
}

@Composable
private fun MobileFilesRenameDialog(
    item: FilesItem,
    operation: FilesFolderOperation,
    draft: MutableState<String>,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val pending = operation is FilesFolderOperation.Loading
    val reloadStarted = operation.renameReloadStarted()
    val renameFailure = operation.renameFailureFor(item.id)
    val focusRequester = remember { FocusRequester() }
    val submit = {
        if (!pending && !reloadStarted) {
            if (draft.value == item.name) onDismiss() else onRename(draft.value)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!pending) onDismiss() },
        title = { Text(stringResource(R.string.mobile_files_rename)) },
        text = {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    enabled = !pending,
                    label = { Text(stringResource(R.string.mobile_files_name)) },
                    isError = renameFailure != null,
                    supportingText = {
                        if (renameFailure != null) {
                            Text(
                                text = renameFailure.mobileMessage(),
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
            TextButton(onClick = onDismiss, enabled = !pending) {
                Text(stringResource(R.string.mobile_action_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileFilesActionsSheet(
    item: FilesItem,
    operation: FilesFolderOperation,
    confirmedTrashEnabled: Boolean?,
    currentTrashEnabled: State<Boolean?>,
    downloadStatus: DownloadStatus?,
    canStartCopy: Boolean,
    onRename: () -> Unit,
    onTrash: () -> Unit,
    onConfirmPermanentDelete: () -> Unit,
    onDismiss: () -> Unit,
    onMoveItem: ((FilesItem) -> Unit)?,
    onDownloadItem: ((FilesItem) -> Unit)?,
    onShareItem: ((FilesItem) -> Unit)?,
    onCopyItem: ((FilesItem) -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                        .clickable(enabled = operation.canStartOperation, role = Role.Button, onClick = onRename),
                )
            }
            if (onDownloadItem != null && item.isPlayable) {
                MobileFilesDownloadAction(item, downloadStatus) {
                    onDownloadItem(item)
                    onDismiss()
                }
            }
            if (onShareItem != null && !item.isFolder && item.id.value > 0L) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.mobile_files_share)) },
                    supportingContent = { Text(stringResource(R.string.mobile_files_share_description)) },
                    modifier = Modifier
                        .testTag(MOBILE_FILES_SHARE_ACTION_TAG)
                        .clickable(role = Role.Button) {
                            onShareItem(item)
                            onDismiss()
                        },
                )
            }
            if (onCopyItem != null && item.canMakeCopy) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.mobile_files_make_copy)) },
                    modifier = Modifier
                        .testTag(MOBILE_FILES_COPY_ACTION_TAG)
                        .clickable(enabled = canStartCopy, role = Role.Button) {
                            onCopyItem(item)
                            onDismiss()
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
                        onDismiss()
                    },
                )
            }
            if (item.acceptsOwnerActions) {
                MobileFilesDeleteAction(
                    item = item,
                    operation = operation,
                    confirmedTrashEnabled = confirmedTrashEnabled,
                    currentTrashEnabled = currentTrashEnabled,
                    onTrash = onTrash,
                    onConfirmPermanentDelete = onConfirmPermanentDelete,
                )
            }
        }
    }
}

// A completed or running download shows its state; the row stays informational.
@Composable
private fun MobileFilesDownloadAction(
    item: FilesItem,
    downloadStatus: DownloadStatus?,
    onClick: () -> Unit,
) {
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
            .clickable(enabled = downloadable && item.id.value > 0L, role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun MobileFilesDeleteAction(
    item: FilesItem,
    operation: FilesFolderOperation,
    confirmedTrashEnabled: Boolean?,
    currentTrashEnabled: State<Boolean?>,
    onTrash: () -> Unit,
    onConfirmPermanentDelete: () -> Unit,
) {
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
                when (currentTrashEnabled.value) {
                    true -> onTrash()
                    false -> onConfirmPermanentDelete()
                    null -> Unit
                }
            }
            .padding(bottom = 24.dp),
    )
}

// The rename this sheet submitted finished in a request it has not seen before.
private fun FilesRenameCompletion?.confirms(
    itemId: FilesItemId,
    submittedName: String?,
    previousRequestValue: Long?,
): Boolean =
    submittedName != null &&
        this?.intent == FilesFolderOperationIntent.Rename(itemId, submittedName) &&
        requestId.value != previousRequestValue

private fun confirmedDeleteMode(trashEnabled: Boolean?): FilesDeleteMode? =
    when (trashEnabled) {
        true -> FilesDeleteMode.TRASH
        false -> FilesDeleteMode.PERMANENT
        null -> null
    }

private fun FilesFolderOperation.renameReloadStarted(): Boolean =
    when (this) {
        is FilesFolderOperation.Loading ->
            intent is FilesFolderOperationIntent.Rename && phase == FilesFolderOperationPhase.RELOADING
        is FilesFolderOperation.Failed ->
            intent is FilesFolderOperationIntent.Rename && phase == FilesFolderOperationPhase.RELOADING
        FilesFolderOperation.Idle -> false
    }

private fun FilesFolderOperation.renameFailureFor(itemId: FilesItemId): PutioFailure? =
    (this as? FilesFolderOperation.Failed)?.takeIf {
        (it.intent as? FilesFolderOperationIntent.Rename)?.itemId == itemId &&
            it.phase == FilesFolderOperationPhase.RENAMING
    }?.failure
