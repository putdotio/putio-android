package io.putdotio.android.files

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.MobileDialogDismissButton
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.putioMono
import io.putdotio.android.downloads.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import io.putdotio.android.MobileSheetHandle
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.withFrameNanos
import io.putdotio.android.design.R as DesignR

internal const val MOBILE_FILES_RENAME_FIELD_TAG = "mobile-files-rename-field"
internal const val MOBILE_FILES_RENAME_CONFIRM_TAG = "mobile-files-rename-confirm"
internal const val MOBILE_FILES_PLAY_ACTION_TAG = "mobile-files-play-action"
internal const val MOBILE_FILES_DOWNLOAD_ACTION_TAG = "mobile-files-download-action"
internal const val MOBILE_FILES_SHARE_ACTION_TAG = "mobile-files-share-action"
internal const val MOBILE_FILES_VLC_ACTION_TAG = "mobile-files-vlc-action"
internal const val MOBILE_FILES_SELECT_ACTION_TAG = "mobile-files-select-action"
internal const val MOBILE_FILES_WATCHED_ACTION_TAG = "mobile-files-watched-action"
internal const val MOBILE_FILES_RENAME_ACTION_TAG = "mobile-files-rename-action"
internal const val MOBILE_FILES_COPY_ACTION_TAG = "mobile-files-copy-action"
internal const val MOBILE_FILES_MOVE_ACTION_TAG = "mobile-files-move-action"
internal const val MOBILE_FILES_PUBLIC_LINK_ACTION_TAG = "mobile-files-public-link-action"
internal const val MOBILE_FILES_DELETE_ACTION_TAG = "mobile-files-delete-action"

/**
 * What the actions sheet offers beyond the browser's own events; each null action stays hidden.
 * [onSetWatched] is null while the account's resume setting is unknown or off, since "watched" is
 * the saved position that setting keeps.
 */
internal class MobileFilesItemActions(
    val onOpenInVlc: ((FilesItem) -> Unit)? = null,
    val onSelect: ((FilesItem) -> Unit)? = null,
    val onSetWatched: ((FilesItem, Boolean) -> Unit)? = null,
    /** Restores a confirmed Trash move from the snackbar's Undo. */
    val onUndoTrash: ((FilesDeleteOutcome) -> Unit)? = null,
    /** Actions that did not go through, and a copied link, for the Files snackbar. */
    val notices: Flow<MobileFilesNotice> = emptyFlow(),
    /** A link copied from the Exclusive access sheet, which closes so the snackbar can say so. */
    val onLinkCopied: (() -> Unit)? = null,
) {
    /** The resume setting [onSetWatched] needs is also what makes playback start from a saved position. */
    val resumes: Boolean get() = onSetWatched != null
}

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
    onPublicLinkItem: ((FilesItem) -> Unit)? = null,
    onPlayItem: ((FilesItem) -> Unit)? = null,
    itemActions: MobileFilesItemActions = MobileFilesItemActions(),
    /** The friend whose folder holds a shared item, named in the sheet's meta. */
    sharedBy: String? = null,
    /**
     * Rename and the delete action change the listing they run from; a sheet opened outside its
     * folder, as from Search, leaves them out.
     */
    listingActions: Boolean = true,
) {
    val failed = operation as? FilesFolderOperation.Failed
    val failedRename = (failed?.intent as? FilesFolderOperationIntent.Rename)?.takeIf { it.itemId == item.id }
    var editing by rememberSaveable { mutableStateOf(false) }
    val draft = rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf((failedRename?.name ?: item.name).withNameSelected(item.isFolder))
    }
    var submittedName by rememberSaveable { mutableStateOf<String?>(null) }
    var previousCompletionRequestValue by rememberSaveable { mutableStateOf<Long?>(null) }
    // Only permanent deletion confirms; Trash keeps the item, and its snackbar offers Undo.
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
            sharedBy = sharedBy,
            onDismiss = dismiss,
            tiles = {
                MobileFilesActionTiles(
                    item = item,
                    resumes = itemActions.resumes,
                    downloadStatus = downloadStatus,
                    onPlayItem = onPlayItem,
                    onDownloadItem = onDownloadItem,
                    onShareItem = onShareItem,
                    onDismiss = onDismiss,
                )
            },
        ) {
            MobileFilesActionRows(
                item = item,
                operation = operation,
                itemActions = itemActions,
                canStartCopy = canStartCopy,
                onRename = { editing = true },
                listingActions = listingActions,
                onDismiss = onDismiss,
                onMoveItem = onMoveItem,
                onCopyItem = onCopyItem,
                onPublicLinkItem = onPublicLinkItem,
            )
            MobileFilesDeleteAction(
                item = item,
                shown = listingActions,
                operation = operation,
                confirmedTrashEnabled = confirmedTrashEnabled,
                currentTrashEnabled = currentTrashEnabled,
                onTrash = { trashTapped = true },
                onConfirmPermanentDelete = { confirmingPermanentDelete = true },
            )
        }
    }
}

/**
 * An on-device file while put.io is out of reach: only what works offline, which is playing it.
 * The Download tile reads On this device, so the sheet says why the file is listed.
 */
@Composable
internal fun MobileFilesOfflineActions(
    item: FilesItem,
    onPlayItem: (FilesItem) -> Unit,
    onDismiss: () -> Unit,
) {
    MobileFilesActionsSheet(
        item = item,
        sharedBy = null,
        onDismiss = onDismiss,
        tiles = {
            MobileFilesActionTiles(
                item = item,
                resumes = false,
                downloadStatus = DownloadStatus.Completed(item.sizeBytes),
                onPlayItem = onPlayItem,
                onDownloadItem = {},
                onShareItem = null,
                onDismiss = onDismiss,
            )
        },
    ) {}
}

@Composable
private fun MobileFilesRenameDialog(
    item: FilesItem,
    operation: FilesFolderOperation,
    draft: MutableState<TextFieldValue>,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val pending = operation is FilesFolderOperation.Loading
    val reloadStarted = operation.renameReloadStarted()
    val renameFailure = operation.renameFailureFor(item.id)
    val focusRequester = remember { FocusRequester() }
    val submit = {
        if (!pending && !reloadStarted) {
            val name = draft.value.text
            if (name == item.name) onDismiss() else onRename(name)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!pending) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp),
        title = { Text(stringResource(R.string.mobile_files_rename)) },
        text = {
            LaunchedEffect(Unit) {
                withFrameNanos {}
                focusRequester.requestFocus()
            }
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    enabled = !pending,
                    label = { Text(stringResource(R.string.mobile_files_name)) },
                    isError = renameFailure != null,
                    supportingText = renameFailure?.let { failure ->
                        {
                            Text(
                                text = failure.mobileMessage(),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                        focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag(MOBILE_FILES_RENAME_FIELD_TAG),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = submit,
                enabled = !pending && !reloadStarted,
                modifier = Modifier.testTag(MOBILE_FILES_RENAME_CONFIRM_TAG),
            ) {
                Text(stringResource(if (pending) R.string.mobile_files_renaming else R.string.mobile_files_rename))
            }
        },
        dismissButton = { MobileDialogDismissButton(onClick = onDismiss, enabled = !pending) },
    )
}

/**
 * The name with its stem selected, so typing replaces the name and keeps the extension; a folder,
 * or a name whose only dot leads it, selects in full.
 */
internal fun String.withNameSelected(isFolder: Boolean): TextFieldValue {
    val extension = lastIndexOf('.')
    val end = if (isFolder || extension <= 0) length else extension
    return TextFieldValue(this, TextRange(0, end))
}

/** The sheet: the file's glyph, full name and meta, its tiles, then its rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileFilesActionsSheet(
    item: FilesItem,
    sharedBy: String?,
    onDismiss: () -> Unit,
    tiles: @Composable () -> Unit,
    rows: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { MobileSheetHandle() },
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = SHEET_BOTTOM_PADDING),
        ) {
            MobileFilesSheetHeader(item, sharedBy)
            tiles()
            rows()
        }
    }
}

@Composable
private fun MobileFilesSheetHeader(item: FilesItem, sharedBy: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(HEADER_LEADING_SIZE), contentAlignment = Alignment.Center) {
            FileTypeIcon(item.type, Modifier.size(HEADER_ICON_SIZE))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // The whole name, wrapped and selectable: the list cut it to one line.
            SelectionContainer {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            MobileFilesSheetMeta(item, sharedBy)
        }
    }
}

@Composable
private fun MobileFilesSheetMeta(item: FilesItem, sharedBy: String?) {
    val context = LocalContext.current
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val size = if (item.isFolder) null else formatFileSize(item.sizeBytes)
    val second = sharedBy?.let { stringResource(R.string.mobile_files_shared_by, it) }
        ?: item.createdAt.toDisplayDate(context)
    Row(horizontalArrangement = Arrangement.spacedBy(META_GAP), verticalAlignment = Alignment.CenterVertically) {
        size?.let { Text(it, style = style.putioMono(), color = color, maxLines = 1) }
        if (size != null && second != null) {
            Text("·", style = style, color = color, modifier = Modifier.clearAndSetSemantics {})
        }
        second?.let { Text(it, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Play or Resume, Download and Share as 72dp tiles; the ones the file allows share the row. */
@Composable
private fun MobileFilesActionTiles(
    item: FilesItem,
    resumes: Boolean,
    downloadStatus: DownloadStatus?,
    onPlayItem: ((FilesItem) -> Unit)?,
    onDownloadItem: ((FilesItem) -> Unit)?,
    onShareItem: ((FilesItem) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val playable = item.isPlayable && item.id.value > 0L
    val play = onPlayItem?.takeIf { playable }
    val download = onDownloadItem?.takeIf { playable }
    val share = onShareItem?.takeIf { !item.isFolder && item.id.value > 0L }
    if (play == null && download == null && share == null) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        play?.let {
            val resume = resumes && item.playback?.resumesPlayback() == true
            MobileFilesActionTile(
                icon = DesignR.drawable.ic_ph_play_fill,
                label = stringResource(if (resume) R.string.mobile_files_resume else R.string.mobile_files_play),
                tag = MOBILE_FILES_PLAY_ACTION_TAG,
            ) {
                onDismiss()
                it(item)
            }
        }
        download?.let { MobileFilesDownloadTile(item, downloadStatus, it, onDismiss) }
        share?.let {
            MobileFilesActionTile(
                icon = R.drawable.ic_ph_share_network,
                label = stringResource(R.string.mobile_files_share_action),
                tag = MOBILE_FILES_SHARE_ACTION_TAG,
            ) {
                it(item)
                onDismiss()
            }
        }
    }
}

// A completed or running download names its state; the tile then does nothing.
@Composable
private fun RowScope.MobileFilesDownloadTile(
    item: FilesItem,
    downloadStatus: DownloadStatus?,
    onDownloadItem: (FilesItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val downloadable = downloadStatus == null || downloadStatus is DownloadStatus.Failed ||
        downloadStatus == DownloadStatus.Missing
    val (icon, label) = when (downloadStatus) {
        null, is DownloadStatus.Failed, DownloadStatus.Missing ->
            R.drawable.ic_ph_arrow_circle_down to R.string.mobile_files_download_action
        is DownloadStatus.Completed -> R.drawable.ic_ph_arrow_circle_down_fill to R.string.mobile_files_downloaded
        else -> R.drawable.ic_ph_arrow_circle_down to R.string.mobile_files_downloading
    }
    MobileFilesActionTile(
        icon = icon,
        label = stringResource(label),
        tag = MOBILE_FILES_DOWNLOAD_ACTION_TAG,
        enabled = downloadable,
        iconTint = if (downloadStatus is DownloadStatus.Completed) PutioDesignTokens.yellowSolid else Color.Unspecified,
    ) {
        onDownloadItem(item)
        onDismiss()
    }
}

@Composable
private fun RowScope.MobileFilesActionTile(
    @DrawableRes icon: Int,
    label: String,
    tag: String,
    enabled: Boolean = true,
    iconTint: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    val content = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = TILE_HEIGHT)
            .clip(RoundedCornerShape(PutioRadius.largeIncreased))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (iconTint == Color.Unspecified) content else iconTint,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA),
        )
    }
}

/** The 56dp rows, in the board's order; each shows only when the item allows it. */
@Composable
private fun MobileFilesActionRows(
    item: FilesItem,
    operation: FilesFolderOperation,
    itemActions: MobileFilesItemActions,
    canStartCopy: Boolean,
    onRename: () -> Unit,
    listingActions: Boolean,
    onDismiss: () -> Unit,
    onMoveItem: ((FilesItem) -> Unit)?,
    onCopyItem: ((FilesItem) -> Unit)?,
    onPublicLinkItem: ((FilesItem) -> Unit)?,
) {
    val listed = item.id.value > 0L
    val owner = item.acceptsOwnerActions && listed
    // Each action closes the sheet once it has started.
    val then = { action: (FilesItem) -> Unit ->
        {
            action(item)
            onDismiss()
        }
    }
    itemActions.onOpenInVlc?.takeIf { item.opensInVlc }?.let {
        MobileFilesActionRow(R.drawable.ic_ph_traffic_cone, R.string.mobile_files_open_in_vlc,
            MOBILE_FILES_VLC_ACTION_TAG, onClick = then(it))
    }
    itemActions.onSelect?.takeIf { listed }?.let {
        MobileFilesActionRow(R.drawable.ic_ph_check_square, R.string.mobile_files_select,
            MOBILE_FILES_SELECT_ACTION_TAG, onClick = then(it))
    }
    if (onCopyItem != null && item.canMakeCopy) {
        MobileFilesActionRow(R.drawable.ic_ph_copy_simple, R.string.mobile_files_make_copy,
            MOBILE_FILES_COPY_ACTION_TAG, enabled = canStartCopy, onClick = then(onCopyItem))
    }
    itemActions.onSetWatched?.takeIf { owner }?.let { MobileFilesWatchedRow(item, it, onDismiss) }
    MobileFilesOwnerRows(item, operation, onRename.takeIf { listingActions }, onDismiss, onMoveItem, onPublicLinkItem)
}

/**
 * Mark as watched for a video or audio file not started or part-way through, which saves its end;
 * Mark as unwatched once it is finished, or played with no known length, which drops the position.
 */
@Composable
private fun MobileFilesWatchedRow(item: FilesItem, setWatched: (FilesItem, Boolean) -> Unit, onDismiss: () -> Unit) {
    if (!item.isPlayable) return
    val progress = item.playback
    val duration = progress?.durationSeconds
    // Played with no known length can only be cleared, since watched is a position at the end.
    val finished = progress != null && progress.isWatched && (duration == null || !progress.resumesPlayback())
    // Watched is the position at the file's end, so it needs a known length.
    if (!finished && duration == null) return
    MobileFilesActionRow(
        if (finished) R.drawable.ic_ph_eye_slash else R.drawable.ic_ph_eye,
        if (finished) R.string.mobile_files_mark_unwatched else R.string.mobile_files_mark_watched,
        MOBILE_FILES_WATCHED_ACTION_TAG,
    ) {
        setWatched(item, !finished)
        onDismiss()
    }
}

/** Rename, Move and Exclusive link, which only the item's owner has. */
@Composable
private fun MobileFilesOwnerRows(
    item: FilesItem,
    operation: FilesFolderOperation,
    onRename: (() -> Unit)?,
    onDismiss: () -> Unit,
    onMoveItem: ((FilesItem) -> Unit)?,
    onPublicLinkItem: ((FilesItem) -> Unit)?,
) {
    if (!item.acceptsOwnerActions) return
    val listed = item.id.value > 0L
    onRename?.let {
        MobileFilesActionRow(R.drawable.ic_ph_pencil_simple, R.string.mobile_files_rename,
            MOBILE_FILES_RENAME_ACTION_TAG, enabled = operation.canStartOperation, onClick = it)
    }
    onMoveItem?.let { move ->
        MobileFilesActionRow(R.drawable.ic_ph_folder_simple_dashed, R.string.mobile_files_move,
            MOBILE_FILES_MOVE_ACTION_TAG, enabled = listed && operation.canStartOperation) {
            move(item)
            onDismiss()
        }
    }
    // Owner-only, as on web: a friend's item and the shared folders are not the viewer's to share.
    onPublicLinkItem?.takeIf { listed }?.let { link ->
        MobileFilesActionRow(R.drawable.ic_ph_link, R.string.mobile_files_exclusive_link,
            MOBILE_FILES_PUBLIC_LINK_ACTION_TAG) {
            link(item)
            onDismiss()
        }
    }
}

@Composable
private fun MobileFilesActionRow(
    @DrawableRes icon: Int,
    label: Int,
    tag: String,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
    supporting: String? = null,
    onClick: () -> Unit,
) {
    val textColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    val iconColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag(tag)
            .then(if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA)),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = textColor)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Last, in red, after a divider: Move to trash, or Delete permanently when the account turns Trash off. */
@Composable
private fun MobileFilesDeleteAction(
    item: FilesItem,
    shown: Boolean,
    operation: FilesFolderOperation,
    confirmedTrashEnabled: Boolean?,
    currentTrashEnabled: State<Boolean?>,
    onTrash: () -> Unit,
    onConfirmPermanentDelete: () -> Unit,
) {
    // Only the owner deletes, and only from the listing the item is in.
    if (!shown || !item.acceptsOwnerActions) return
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    MobileFilesActionRow(
        icon = R.drawable.ic_ph_trash,
        label = if (confirmedTrashEnabled == false) {
            R.string.mobile_files_delete_permanently
        } else {
            R.string.mobile_files_trash
        },
        tag = MOBILE_FILES_DELETE_ACTION_TAG,
        enabled = confirmedTrashEnabled != null && item.id.value > 0L && operation.canStartOperation,
        color = PutioDesignTokens.redTextSecondary,
        supporting = stringResource(R.string.mobile_files_delete_settings_unknown)
            .takeIf { confirmedTrashEnabled == null },
    ) {
        when (currentTrashEnabled.value) {
            true -> onTrash()
            false -> onConfirmPermanentDelete()
            null -> Unit
        }
    }
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

/**
 * The Delete key on a focused row, acting as the sheet's delete row would: Move to trash goes at once,
 * Delete permanently confirms first. [mode] is the setting when the key was pressed; if the account's
 * Trash setting has changed since, nothing is sent.
 */
@Composable
internal fun MobileFilesKeyDelete(
    item: FilesItem,
    folderId: FilesItemId,
    mode: FilesDeleteMode,
    confirmedTrashEnabled: Boolean?,
    operation: FilesFolderOperation,
    onEvent: (FilesBrowserEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    var submitted by rememberSaveable { mutableStateOf(false) }
    val settingMatches = confirmedDeleteMode(confirmedTrashEnabled) == mode
    val submit = {
        if (!submitted && settingMatches && operation.canStartOperation) {
            submitted = true
            onEvent(FilesBrowserEvent.Delete(folderId, item.id, mode))
        }
        onDismiss()
    }
    if (mode == FilesDeleteMode.PERMANENT && settingMatches) {
        MobileFilesDeleteConfirmation(
            enabled = !submitted && operation.canStartOperation,
            onDismiss = onDismiss,
            onConfirm = submit,
        )
    } else {
        SideEffect(submit)
    }
}

/**
 * Whether opening the file offers to resume: a saved position more than [FINISHED_WITHIN_SECONDS]
 * before the end, the player's own rule; with no known length, any saved position.
 */
private fun FilesPlaybackProgress.resumesPlayback(): Boolean {
    val duration = durationSeconds
    return startFromSeconds > 0.0 && (duration == null || startFromSeconds < duration - FINISHED_WITHIN_SECONDS)
}

internal fun confirmedDeleteMode(trashEnabled: Boolean?): FilesDeleteMode? =
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

private const val FINISHED_WITHIN_SECONDS = 10.0
private val HEADER_LEADING_SIZE = 40.dp
private val HEADER_ICON_SIZE = 26.dp
private val META_GAP = 6.dp
private val TILE_HEIGHT = 72.dp
private val ROW_HEIGHT = 56.dp
/** The sheet adds the navigation bar inset itself; over a gesture bar this makes the board's 28dp. */
private val SHEET_BOTTOM_PADDING = 4.dp
private const val DISABLED_ALPHA = 0.38f
