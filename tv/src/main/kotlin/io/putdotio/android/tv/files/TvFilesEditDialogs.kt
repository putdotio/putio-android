package io.putdotio.android.tv.files

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesMoveDestinationEvent
import io.putdotio.android.files.FilesMoveDestinationState
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.authoritativeSessionFailure
import io.putdotio.android.files.confirms
import io.putdotio.android.files.nameStemEnd
import io.putdotio.android.files.renameFailureFor
import io.putdotio.android.files.renameReloadStarted
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvButtonStyle
import io.putdotio.android.tv.TvConfirmDialog
import io.putdotio.android.tv.TvEmptyState
import io.putdotio.android.tv.TvFormDialog
import io.putdotio.android.tv.TvListSkeleton
import io.putdotio.android.tv.TvLoadFailedState
import io.putdotio.android.tv.TvNoticeDialog
import io.putdotio.android.tv.TvSheetOption
import io.putdotio.android.tv.TvSheetRows
import io.putdotio.android.tv.TvSideSheet
import io.putdotio.android.tv.TvSnackbarMessage
import io.putdotio.android.tv.TvSnackbarState
import io.putdotio.android.tv.TvTextFieldFrame
import io.putdotio.android.tv.TvTextInput
import io.putdotio.android.tv.TV_TEXT_FIELD_PADDING
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.tvAccent

internal const val TV_FILES_RENAME_FIELD_TAG = "tv-files-rename-field"
internal const val TV_FILES_RENAME_CONFIRM_TAG = "tv-files-rename-confirm"
internal const val TV_FILES_MOVE_HERE_TAG = "tv-files-move-here"

@Composable
internal fun TvFilesListingRename(
    item: FilesItem,
    folder: FilesFolderState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onClose: () -> Unit,
) {
    val operation = folder.operation
    val failedRename = (operation as? FilesFolderOperation.Failed)?.intent as? FilesFolderOperationIntent.Rename
    var submittedName by rememberSaveable(item.id.value) { mutableStateOf<String?>(null) }
    var previousRequest by rememberSaveable(item.id.value) { mutableStateOf<Long?>(null) }
    LaunchedEffect(folder.renameCompletion, submittedName, previousRequest) {
        if (folder.renameCompletion.confirms(item.id, submittedName, previousRequest)) onClose()
    }
    val dismiss = {
        if (failedRename?.itemId == item.id) onEvent(FilesBrowserEvent.AbandonRename(folder.folder.id, failedRename))
        onClose()
    }
    TvRenameDialog(
        name = item.name,
        isFolder = item.isFolder,
        initialDraft = failedRename?.takeIf { it.itemId == item.id }?.name,
        pending = (operation as? FilesFolderOperation.Loading)?.intent is FilesFolderOperationIntent.Rename,
        blocked = operation is FilesFolderOperation.Loading || operation.renameReloadStarted(),
        failure = operation.renameFailureFor(item.id),
        onRename = { name ->
            submittedName = name
            previousRequest = folder.renameCompletion?.requestId?.value
            onEvent(FilesBrowserEvent.Rename(folder.folder.id, item.id, name))
        },
        onDismiss = dismiss,
    )
}

@Composable
internal fun TvRenameDialog(
    name: String,
    isFolder: Boolean,
    initialDraft: String?,
    pending: Boolean,
    blocked: Boolean,
    failure: PutioFailure?,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTextFieldState(initialDraft ?: name, TextRange(0, name.nameStemEnd(isFolder)))
    val confirmFocus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val canSubmit = !pending && !blocked && state.text.isNotBlank()
    val submit = {
        if (canSubmit) {
            val draft = state.text.toString()
            if (draft == name) onDismiss() else onRename(draft)
        }
    }
    TvFormDialog(
        title = stringResource(R.string.tv_files_action_rename),
        confirmLabel = stringResource(if (pending) R.string.tv_files_renaming else R.string.tv_files_action_rename),
        onConfirm = submit,
        onDismiss = { if (!pending) onDismiss() },
        confirmEnabled = canSubmit,
        confirmFocus = confirmFocus,
        confirmTag = TV_FILES_RENAME_CONFIRM_TAG,
        dimAmount = FILES_SHEET_SCRIM.alpha,
    ) { focus ->
        TvTextFieldFrame(interaction, ringGap = PutioDesignTokens.componentBgHover) {
            TvTextInput(
                state = state,
                label = stringResource(R.string.tv_files_rename_field),
                placeholder = null,
                imeAction = ImeAction.Done,
                interaction = interaction,
                testTag = TV_FILES_RENAME_FIELD_TAG,
                onSubmit = submit,
                readOnly = pending,
                modifier = Modifier
                    .padding(horizontal = TV_TEXT_FIELD_PADDING)
                    .focusRequester(focus)
                    .focusProperties { down = confirmFocus },
            )
        }
        if (failure != null) {
            Text(
                text = renameErrorText(failure),
                style = PutioTypeScale.tvMeta,
                color = PutioDesignTokens.redTextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = RENAME_ERROR_GAP)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun renameErrorText(failure: PutioFailure): String =
    (failure as? PutioFailure.ApiRejected)?.let { failure.tvMessageText() }
        ?: stringResource(R.string.tv_files_rename_error)

@Composable
internal fun TvFilesMovePicker(
    item: FilesItem,
    sourceFolderId: FilesItemId,
    edits: TvFilesEdits,
    canSubmit: Boolean,
    onAuthenticationRequired: suspend () -> Unit,
    onConfirm: (destination: FilesItemId) -> Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(item.id, sourceFolderId, edits) {
        edits.moveDestinations(item, sourceFolderId, scope)
    }
    var finished by remember(controller) { mutableStateOf(false) }
    DisposableEffect(controller) {
        onDispose {
            finished = true
            controller.close()
        }
    }
    val destination by controller.state.collectAsState()
    val sessionFailure = destination.current.content.authoritativeSessionFailure() != null
    LaunchedEffect(sessionFailure) { if (sessionFailure) onAuthenticationRequired() }
    val confirm = {
        val current = controller.state.value
        val submittable = canSubmit && !sessionFailure
        if (!finished && submittable && current.canMoveHere) {
            finished = true
            if (onConfirm(current.current.folder.id)) {
                edits.destinationChosen(current.path)
                onDismiss()
            } else {
                finished = false
            }
        }
    }
    TvFilesMoveSheet(
        state = destination,
        onEvent = { controller.dispatch(it) },
        onConfirm = confirm,
        canSubmit = canSubmit && !finished && !sessionFailure,
        onDismiss = {
            finished = true
            onDismiss()
        },
    )
}

@Composable
internal fun TvFilesMoveSheet(
    state: FilesMoveDestinationState,
    onEvent: (FilesMoveDestinationEvent) -> Unit,
    onConfirm: () -> Unit,
    canSubmit: Boolean,
    onDismiss: () -> Unit,
) {
    val rememberedRows = remember { mutableMapOf<Long, Long>() }
    val currentState by rememberUpdatedState(state)
    TvSideSheet(
        onDismiss = {
            if (currentState.canNavigateBack) onEvent(FilesMoveDestinationEvent.NavigateBack) else onDismiss()
        },
        returnFocus = null,
        scrim = FILES_SHEET_SCRIM,
        title = stringResource(R.string.tv_files_move_title),
    ) { initialFocus ->
        TvMoveLocation(state)
        val folderId = state.current.folder.id.value
        val rowFocus = remember(folderId) { FocusRequester() }
        val moveHereFocus = remember { FocusRequester() }
        val retryFocus = remember { FocusRequester() }
        val pagingFocus = remember { FocusRequester() }
        val content = state.current.content
        val items = content.tvMoveRows()
        val target = rememberedRows[folderId]?.takeIf { id -> items.any { it.id.value == id } }
            ?: items.firstOrNull()?.id?.value
        val lastRowFocus = remember(folderId) { FocusRequester() }
        val pagingHeldFocus = remember(folderId) { mutableStateOf(false) }
        TvMovePagingHandOff((content as? FilesContent.Ready)?.paging, pagingHeldFocus, lastRowFocus)
        LaunchedEffect(folderId, content is FilesContent.Loading, target) {
            if (content is FilesContent.Loading) return@LaunchedEffect
            withFrameNanos {}
            when {
                content is FilesContent.Failed -> retryFocus.requestFocusIfAttached()
                target != null -> rowFocus.requestFocusIfAttached()
                else -> moveHereFocus.requestFocusIfAttached()
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            when (content) {
                is FilesContent.Loading -> TvListSkeleton(
                    Modifier
                        .focusRequester(initialFocus)
                        .focusable(),
                )
                is FilesContent.Failed -> TvLoadFailedState(
                    failure = content.failure,
                    headline = stringResource(R.string.tv_files_move_error_title),
                    message = content.failure.tvLoadFailedMessage(),
                    onRetry = { onEvent(FilesMoveDestinationEvent.Retry) },
                    modifier = Modifier.weight(1f),
                    actionFocus = retryFocus,
                    claimFocus = true,
                )
                is FilesContent.Empty -> TvEmptyState(
                    glyph = PutioEmptyGlyph.EmptyFolder,
                    headline = stringResource(R.string.tv_files_move_empty_title),
                    message = stringResource(R.string.tv_files_move_empty_message),
                    modifier = Modifier.weight(1f),
                )
                is FilesContent.Ready -> TvSheetRows {
                    items.forEach { folder ->
                        TvMoveFolderRow(
                            folder = folder,
                            enabled = state.canOpenFolder(folder.id),
                            modifier = Modifier
                                .focusRequesterIf(folder.id.value == target, rowFocus)
                                .focusRequesterIf(folder.id == items.last().id, lastRowFocus),
                            onOpen = {
                                rememberedRows[folderId] = folder.id.value
                                onEvent(FilesMoveDestinationEvent.OpenFolder(folder.id))
                            },
                        )
                    }
                    TvMovePaging(content.paging, pagingFocus, pagingHeldFocus, onEvent)
                }
            }
        }
        TvButton(
            onClick = onConfirm,
            enabled = canSubmit && state.canMoveHere,
            style = TvButtonStyle.Filled,
            modifier = Modifier
                .padding(top = MOVE_HERE_GAP)
                .fillMaxWidth()
                .focusRequester(moveHereFocus)
                .testTag(TV_FILES_MOVE_HERE_TAG),
        ) {
            Text(
                stringResource(R.string.tv_files_move_here),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TvMovePagingHandOff(
    paging: FilesPaging?,
    pagingHeldFocus: MutableState<Boolean>,
    lastRowFocus: FocusRequester,
) {
    val handOffToLastRow = remember(lastRowFocus) { mutableStateOf(false) }
    if (paging == FilesPaging.Complete && pagingHeldFocus.value) {
        pagingHeldFocus.value = false
        handOffToLastRow.value = true
    }
    LaunchedEffect(handOffToLastRow.value) {
        if (!handOffToLastRow.value) return@LaunchedEffect
        withFrameNanos {}
        lastRowFocus.requestFocusIfAttached()
        handOffToLastRow.value = false
    }
}

@Composable
private fun ColumnScope.TvMoveLocation(state: FilesMoveDestinationState) {
    val root = stringResource(R.string.tv_files_title)
    val path = state.path.joinToString(" / ") { it.name ?: root }
    Row(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ph_folder_open),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = path,
            style = PutioTypeScale.tvMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun TvMoveFolderRow(
    folder: FilesItem,
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvSheetOption(
        label = folder.name,
        detail = null,
        selected = false,
        onClick = onOpen,
        enabled = enabled,
        role = Role.Button,
        trailingIcon = R.drawable.ic_ph_caret_right,
        modifier = modifier,
        labelIsName = true,
        leading = { focused ->
            Icon(
                painter = painterResource(fileTypeIconRes(PutioFileType.FOLDER)),
                contentDescription = null,
                tint = tvAccent(focused),
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun TvMovePaging(
    paging: FilesPaging,
    pagingFocus: FocusRequester,
    pagingHeldFocus: MutableState<Boolean>,
    onEvent: (FilesMoveDestinationEvent) -> Unit,
) {
    val label = when (paging) {
        is FilesPaging.Available -> R.string.tv_files_move_more
        is FilesPaging.Loading -> R.string.tv_files_loading_more
        is FilesPaging.Failed -> R.string.tv_files_retry
        FilesPaging.Complete -> null
    } ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TvButton(
            onClick = {
                when (paging) {
                    is FilesPaging.Available -> onEvent(FilesMoveDestinationEvent.LoadNextPage)
                    is FilesPaging.Failed -> onEvent(FilesMoveDestinationEvent.Retry)
                    is FilesPaging.Loading, FilesPaging.Complete -> Unit
                }
            },
            modifier = Modifier
                .focusRequester(pagingFocus)
                .onFocusChanged { pagingHeldFocus.value = it.isFocused },
        ) { Text(stringResource(label)) }
    }
}

private fun FilesContent.tvMoveRows(): List<FilesItem> =
    (this as? FilesContent.Ready)?.items.orEmpty()

private fun FocusRequester.requestFocusIfAttached() {
    runCatching { requestFocus() }
}

@Stable
internal class TvItemEditTarget {
    var renaming by mutableStateOf<FilesItem?>(null)
    var moving by mutableStateOf<FilesItem?>(null)
    var confirmingDelete by mutableStateOf<FilesItem?>(null)
    var trashPressed by mutableStateOf<FilesItem?>(null)

    val open: Boolean get() = renaming != null || moving != null || confirmingDelete != null

    fun press(action: TvFilesAction, item: FilesItem) {
        when (action) {
            TvFilesAction.Rename -> renaming = item
            TvFilesAction.Move -> moving = item
            is TvFilesAction.Delete -> if (action.trash) trashPressed = item else confirmingDelete = item
            else -> Unit
        }
    }
}

@Composable
internal fun TvItemEditLayer(
    target: TvItemEditTarget,
    edits: TvFilesEdits,
    trashEnabled: Boolean?,
    snackbar: TvSnackbarState?,
    onAuthenticationRequired: suspend () -> Unit,
) {
    val state by edits.state.collectAsState()
    val stale = rememberStaleEditResult(edits)
    val busy = edits.rememberBusy()
    val busyNotice = stringResource(R.string.tv_files_edit_busy)
    val startDelete = { item: FilesItem, mode: FilesDeleteMode ->
        if (!edits.delete(item, mode)) snackbar?.show(TvSnackbarMessage(text = busyNotice))
    }
    val moved = (state as? TvItemEditState.Moved)?.let { stringResource(R.string.tv_files_move_moved, it.itemName) }
    LaunchedEffect(moved) {
        if (moved != null) {
            snackbar?.show(TvSnackbarMessage(text = moved))
            edits.acknowledge()
        }
    }
    val pressedTrash = target.trashPressed
    if (pressedTrash != null) {
        SideEffect {
            target.trashPressed = null
            if (trashEnabled == true) startDelete(pressedTrash, FilesDeleteMode.TRASH)
        }
    }
    target.renaming?.let { item ->
        val attempt = (state as? TvItemEditState.RenameFailed)?.takeIf { it.itemId == item.id }
        LaunchedEffect(state) {
            if ((state as? TvItemEditState.Renamed)?.itemId == item.id) {
                target.renaming = null
                edits.acknowledge()
            }
        }
        TvRenameDialog(
            name = item.name,
            isFolder = item.isFolder,
            initialDraft = attempt?.name,
            pending = (state as? TvItemEditState.Renaming)?.itemId == item.id,
            blocked = busy,
            failure = attempt?.failure,
            onRename = { edits.rename(item, it) },
            onDismiss = {
                target.renaming = null
                edits.acknowledge()
            },
        )
    }
    target.moving?.let { item ->
        TvFilesMovePicker(
            item = item,
            sourceFolderId = item.parentId ?: FilesFolder.Root.id,
            edits = edits,
            canSubmit = !busy,
            onAuthenticationRequired = onAuthenticationRequired,
            onConfirm = { destination -> edits.move(item, destination) },
            onDismiss = { target.moving = null },
        )
    }
    target.confirmingDelete?.let { item ->
        if (trashEnabled == false) {
            TvFilesDeleteDialog(
                item = item,
                onConfirm = {
                    target.confirmingDelete = null
                    startDelete(item, FilesDeleteMode.PERMANENT)
                },
                onDismiss = { target.confirmingDelete = null },
            )
        } else {
            LaunchedEffect(Unit) { target.confirmingDelete = null }
        }
    }
    TvItemEditNotices(state, stale, edits, startDelete)
}

@Composable
private fun rememberStaleEditResult(edits: TvFilesEdits): TvItemEditState {
    val stale = remember(edits) { edits.state.value }
    LaunchedEffect(edits) {
        if (stale is TvItemEditState.TrashLimit || stale is TvItemEditState.Failed) edits.acknowledge()
    }
    return stale
}

@Composable
private fun TvItemEditNotices(
    state: TvItemEditState,
    stale: TvItemEditState,
    edits: TvFilesEdits,
    startDelete: (FilesItem, FilesDeleteMode) -> Unit,
) {
    if (state === stale) return
    (state as? TvItemEditState.TrashLimit)?.let { limit ->
        TvConfirmDialog(
            title = stringResource(R.string.tv_files_trash_limit_title),
            message = stringResource(R.string.tv_files_trash_limit_message, limit.item.name),
            confirmLabel = stringResource(R.string.tv_files_delete_confirm),
            onConfirm = {
                edits.acknowledge()
                startDelete(limit.item, FilesDeleteMode.PERMANENT)
            },
            onDismiss = edits::acknowledge,
            destructive = true,
            balanceMessage = true,
            dimAmount = FILES_SHEET_SCRIM.alpha,
        )
    }
    val failed = state as? TvItemEditState.Failed
    if (failed != null) {
        TvNoticeDialog(
            title = if (failed.itemName != null) {
                stringResource(failed.message, failed.itemName)
            } else {
                stringResource(failed.message)
            },
            message = failed.failure?.tvMessageText(),
            onDismiss = edits::acknowledge,
            okStyle = TvButtonStyle.Filled,
            balanceMessage = true,
            okHugsLabel = true,
            dimAmount = FILES_SHEET_SCRIM.alpha,
        )
    }
}

@Composable
internal fun TvFilesEdits?.rememberBusy(): Boolean = this?.busy?.collectAsState()?.value ?: false

private val RENAME_ERROR_GAP = 8.dp
private val MOVE_HERE_GAP = 8.dp
