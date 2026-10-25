package io.putdotio.android.files

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.FilesFailure
import io.putdotio.android.LoadNextPageNearEnd
import io.putdotio.android.LoadNextPageNow
import io.putdotio.android.MobileErrorState
import io.putdotio.android.MobileKeyCommand
import io.putdotio.android.MobileShortcutScope
import io.putdotio.android.MobileUnhandledKeyEffect
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.apiReason
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.share.MobileFileDragOut
import io.putdotio.android.share.MobileFileDragShadow
import io.putdotio.sdk.files.PutioFolderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import io.putdotio.android.MobileSnackbarHost

internal const val MOBILE_FILES_LIST_TAG = "mobile-files-list"
internal const val MOBILE_FILES_OPERATION_RETRY_TAG = "mobile-files-operation-retry"
internal const val MOBILE_FILES_PAGING_ACTION_TAG = "mobile-files-paging-action"
internal const val MOBILE_FILES_REFRESH_TAG = "mobile-files-refresh"
internal const val MOBILE_FILES_SNACKBAR_TAG = "mobile-files-snackbar"

@Composable
internal fun MobileFilesScreen(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Unit,
    onPlayMedia: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
    confirmedTrashEnabled: Boolean? = null,
    onMoveItem: ((FilesItem) -> Unit)? = null,
    downloads: DownloadsState = DownloadsState(),
    onDownloadItem: ((FilesItem) -> Unit)? = null,
    onShareItem: ((FilesItem) -> Unit)? = null,
    onCopyItem: ((FilesItem) -> Unit)? = null,
    onPublicLinkItem: ((FilesItem) -> Unit)? = null,
    fileDragOut: MobileFileDragOut? = null,
    /** Started videos for the root's Continue watching row; other folders never show it. */
    continueWatching: List<ContinueWatchingEntry> = emptyList(),
    /** Opens the New folder dialog; null where this screen can't create one. */
    onNewFolder: (() -> Unit)? = null,
    /** How a tap opens a file beyond playing media; null leaves only media playable. */
    fileOpening: MobileFilesOpening? = null,
    itemActions: MobileFilesItemActions = MobileFilesItemActions(),
    /** The folder's selection, which the shell's top bar shares; null leaves Select out of the sheet. */
    selection: FilesSelection? = null,
    /** Opens the Move picker for the selected items; null hides the toolbar's Move. */
    onMoveItems: ((List<FilesItem>) -> Unit)? = null,
) {
    val current = state.current
    val atRoot = current.folder.id == FilesFolder.Root.id
    // A retry from the offline state keeps the downloads on screen until put.io answers.
    var offline by remember(state.stack.size, current.folder.id.value) { mutableStateOf(false) }
    val content = current.content
    LaunchedEffect(content) {
        if (content !is FilesContent.Loading) {
            offline = (content as? FilesContent.Failed)?.failure is PutioFailure.NetworkUnavailable
        }
    }
    when (content) {
        is FilesContent.Loading -> MobileFilesStateWithCopy(state, onEvent, itemActions.notices, modifier) {
            if (offline) {
                MobileFilesOfflineWithActions(
                    onDevice = if (atRoot) downloads.onDeviceItems() else emptyList(),
                    onRetry = null,
                    onPlayMedia = onPlayMedia,
                )
            } else {
                MobileFilesSkeleton()
            }
        }

        is FilesContent.Failed -> MobileFilesStateWithCopy(state, onEvent, itemActions.notices, modifier) {
            if (content.failure is PutioFailure.NetworkUnavailable) {
                MobileFilesOfflineWithActions(
                    onDevice = if (atRoot) downloads.onDeviceItems() else emptyList(),
                    onRetry = { onEvent(FilesBrowserEvent.Retry) },
                    onPlayMedia = onPlayMedia,
                    failure = content.failure,
                )
            } else {
                MobileErrorState(
                    title = stringResource(R.string.mobile_state_error_title),
                    message = content.failure.mobileMessage(),
                    retryLabel = stringResource(R.string.mobile_action_retry),
                    onRetry = { onEvent(FilesBrowserEvent.Retry) },
                )
            }
        }

        is FilesContent.Empty,
        is FilesContent.Ready,
        -> key(current.folder.id.value) {
            MobileRefreshableFilesContent(
                state, content, onEvent, onPlayMedia, modifier, confirmedTrashEnabled, onMoveItem, downloads,
                onDownloadItem,
                onShareItem,
                onCopyItem,
                onPublicLinkItem,
                fileDragOut,
                headers = MobileFilesHeaders(
                    continueWatching = if (atRoot) continueWatching else emptyList(),
                    summary = if (atRoot) null else current.summary,
                ),
                onNewFolder = onNewFolder,
                fileOpening = fileOpening,
                itemActions = itemActions,
                selection = selection,
                onMoveItems = onMoveItems,
            )
        }
    }
}

/**
 * How the list opens files beside a viewer or the tablet's detail pane: [onOpenFile] takes every
 * file tap; with [showsDetail] that tap selects the file into the pane, which [selectedFileId] names.
 * [actionsRequests] opens a listed file's sheet from outside the list, as the pane's overflow does.
 */
internal class MobileFilesOpening(
    val onOpenFile: (FilesItem) -> Unit,
    val showsDetail: Boolean = false,
    val selectedFileId: FilesItemId? = null,
    val actionsRequests: Flow<FilesItemId> = emptyFlow(),
)
/** On-device files while put.io is out of reach; each row's sheet offers what works offline. */
@Composable
private fun MobileFilesOfflineWithActions(
    onDevice: List<FilesItem>,
    onRetry: (() -> Unit)?,
    onPlayMedia: (FilesItem) -> Unit,
    failure: Any? = null,
) {
    var sheetItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    MobileFilesOffline(
        onDevice = onDevice,
        onRetry = onRetry,
        onPlayMedia = onPlayMedia,
        failure = failure,
        onActions = { sheetItemId = it.id.value },
    )
    onDevice.firstOrNull { it.id.value == sheetItemId }?.let { item ->
        MobileFilesOfflineActions(item, onPlayItem = onPlayMedia, onDismiss = { sheetItemId = null })
    }
}

/** A copy outlives folder navigation, so its line stays while the folder loads or fails. */
@Composable
private fun MobileFilesStateWithCopy(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Unit,
    notices: Flow<MobileFilesNotice>,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MobileFilesCopyLine(state, onEvent)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
        }
        // Item actions started from a listing can fail while the next folder loads.
        MobileFilesNoticeHost(notices, Modifier.align(Alignment.BottomCenter))
    }
}

/** A copy runs on put.io after the picker closes, so its card sits under the bar of every folder. */
@Composable
private fun MobileFilesCopyLine(state: FilesBrowserState, onEvent: (FilesBrowserEvent) -> Unit) {
    state.copyOutcome?.let {
        MobileFilesCopyStatus(it, onDismiss = { onEvent(FilesBrowserEvent.DismissCopyOutcome) })
    }
}

@Composable
private fun MobileRefreshableFilesContent(
    state: FilesBrowserState,
    content: FilesContent,
    onEvent: (FilesBrowserEvent) -> Unit,
    onPlayMedia: (FilesItem) -> Unit,
    modifier: Modifier,
    confirmedTrashEnabled: Boolean?,
    onMoveItem: ((FilesItem) -> Unit)?,
    downloads: DownloadsState,
    onDownloadItem: ((FilesItem) -> Unit)?,
    onShareItem: ((FilesItem) -> Unit)?,
    onCopyItem: ((FilesItem) -> Unit)?,
    onPublicLinkItem: ((FilesItem) -> Unit)?,
    fileDragOut: MobileFileDragOut?,
    headers: MobileFilesHeaders,
    onNewFolder: (() -> Unit)?,
    fileOpening: MobileFilesOpening?,
    itemActions: MobileFilesItemActions,
    selection: FilesSelection?,
    onMoveItems: ((List<FilesItem>) -> Unit)?,
) {
    val operation = state.current.operation
    val currentOperation by rememberUpdatedState(operation)
    val selecting = selection.isActiveIn(state)
    val currentState by rememberUpdatedState(state)
    val sheetActions = itemActions.selectingIn(selection) { currentState }
    var selectedItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Delete on a focused row goes straight to the sheet's delete step, in the mode the setting had then.
    var keyDelete by rememberSaveable { mutableStateOf<FilesDeleteMode?>(null) }
    var focusedItemId by remember { mutableStateOf<Long?>(null) }
    val items = (content as? FilesContent.Ready)?.items.orEmpty()
    val selectedItem = items.firstOrNull { it.id.value == selectedItemId }
    LaunchedEffect(selectedItemId, selectedItem) {
        if (selectedItem == null) selectedItemId = null
    }
    // While rows are selected, the detail pane and the viewer open no sheet.
    MobileFilesActionsRequests(fileOpening, items, enabled = !selecting) { id ->
        keyDelete = null
        selectedItemId = id.value
    }
    MobileFilesKeyboardEffect(
        selectionOpen = selectedItemId != null,
        selecting = selecting,
        operation = operation,
        focusedItem = items.firstOrNull { it.id.value == focusedItemId },
        confirmedTrashEnabled = confirmedTrashEnabled,
        onRefresh = { onEvent(FilesBrowserEvent.Refresh) },
        onDelete = { item, mode ->
            keyDelete = mode
            selectedItemId = item.id.value
        },
    )
    val dismissSelection = {
        selectedItemId = null
        keyDelete = null
    }
    if (selectedItem != null) {
        key(selectedItem.id.value) {
            val deleteMode = keyDelete
            if (deleteMode != null) {
                MobileFilesKeyDelete(
                    item = selectedItem,
                    folderId = state.current.folder.id,
                    mode = deleteMode,
                    confirmedTrashEnabled = confirmedTrashEnabled,
                    operation = operation,
                    onEvent = onEvent,
                    onDismiss = dismissSelection,
                )
            } else {
                MobileFilesActions(
                    item = selectedItem,
                    folderId = state.current.folder.id,
                    operation = operation,
                    renameCompletion = state.current.renameCompletion,
                    onEvent = onEvent,
                    onDismiss = dismissSelection,
                    confirmedTrashEnabled = confirmedTrashEnabled,
                    onMoveItem = onMoveItem,
                    downloadStatus = downloads.entry(selectedItem.id)?.status,
                    onDownloadItem = onDownloadItem,
                    onShareItem = onShareItem,
                    onCopyItem = onCopyItem,
                    canStartCopy = state.canStartCopy,
                    onPublicLinkItem = onPublicLinkItem,
                    onPlayItem = onPlayMedia,
                    itemActions = sheetActions,
                    sharedBy = state.sharedByName(),
                )
            }
        }
    }
    val listHeaders = headers.unlessSelecting(selecting)
    val isRefreshing =
        operation is FilesFolderOperation.Loading &&
            operation.intent == FilesFolderOperationIntent.Refresh
    val refreshLabel = stringResource(R.string.mobile_files_refresh)
    val refreshAction = CustomAccessibilityAction(refreshLabel) {
        if (selectedItemId == null && currentOperation.canStartOperation) {
            onEvent(FilesBrowserEvent.Refresh)
            true
        } else {
            false
        }
    }

    var toolbarHeight by remember { mutableIntStateOf(0) }
    val toolbarClearance = selectionToolbarClearance(toolbarHeight)
    val snackbarHostState = remember { SnackbarHostState() }
    MobileFilesTrashedAnnouncement(state.current, snackbarHostState, onEvent, itemActions.onUndoTrash)
    MobileFilesNotices(itemActions.notices, snackbarHostState)

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MobileFilesCopyLine(state, onEvent)
            val refreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                state = refreshState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = refreshState,
                        isRefreshing = isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onRefresh = {
                    if (selectedItemId == null && currentOperation.canStartOperation) {
                        onEvent(FilesBrowserEvent.Refresh)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(MOBILE_FILES_REFRESH_TAG)
                    .semantics {
                        if (selectedItemId == null && operation.canStartOperation) {
                            customActions = listOf(refreshAction)
                        }
                    },
            ) {
                when (content) {
                    is FilesContent.Empty -> MobileEmptyFilesContent(
                        paging = content.paging,
                        pagingEnabled = operation == FilesFolderOperation.Idle,
                        onEvent = onEvent,
                        onNewFolder = onNewFolder,
                    )
                    is FilesContent.Ready ->
                        // The depth tells a folder opened from Search apart from the same folder under it. A
                        // header set that grew or shrank restarts the list from the row-relative viewport.
                        key(
                            state.stack.size,
                            state.current.folder.id.value,
                            state.current.viewportGeneration,
                            listHeaders.itemCount,
                        ) {
                            MobileFilesList(
                                content = content,
                                headers = listHeaders,
                                onPlayEntry = { onPlayMedia(it.toFilesItem()) },
                                revealItemId = state.current.revealItemId,
                                pagingEnabled = operation == FilesFolderOperation.Idle,
                                onEvent = onEvent,
                                onPlayMedia = onPlayMedia,
                                onActions = { selectedItemId = it.id.value },
                                hasActions = {
                                    it.hasMobileActions(onDownloadItem != null, onShareItem != null, onCopyItem != null)
                                },
                                operation = operation,
                                downloads = downloads,
                                onItemFocusChanged = { id, focused ->
                                    focusedItemId = focusedItemId.afterFocusChange(id.value, focused)
                                },
                                fileDragOut = fileDragOut,
                                // A touch drag starts after the long press opened the sheet; the drag replaces it.
                                onDragStarted = dismissSelection,
                                fileOpening = fileOpening,
                                selection = selection.listSelection(state, toolbarClearance),
                            )
                        }

                    is FilesContent.Failed,
                    is FilesContent.Loading,
                    -> Unit
                }
            }
            MobileFilesFolderStatus(state, onEvent)
        }
        MobileFilesSelectionToolbarSlot(
            state = state,
            selection = selection,
            downloads = downloads,
            confirmedTrashEnabled = confirmedTrashEnabled,
            actions = MobileFilesSelectionSources(
                onMove = onMoveItems,
                onDownload = onDownloadItem,
                onSetWatched = itemActions.onSetWatched,
                onEvent = onEvent,
            ),
            onHeightChanged = { toolbarHeight = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        MobileSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .testTag(MOBILE_FILES_SNACKBAR_TAG),
        )
    }
}

/**
 * F5 or Ctrl+R refreshes as pull-to-refresh does, and Delete acts on the focused row as its sheet's
 * delete row would; neither runs while a sheet is open or an operation is under way. Delete waits for
 * a known Trash setting by opening the sheet, which says why it cannot delete yet.
 */
@Composable
private fun MobileFilesKeyboardEffect(
    selectionOpen: Boolean,
    selecting: Boolean,
    operation: FilesFolderOperation,
    focusedItem: FilesItem?,
    confirmedTrashEnabled: Boolean?,
    onRefresh: () -> Unit,
    onDelete: (FilesItem, FilesDeleteMode?) -> Unit,
) {
    val idle = !selectionOpen && !selecting && operation.canStartOperation
    MobileUnhandledKeyEffect(MobileShortcutScope.Files) { command ->
        when (command) {
            MobileKeyCommand.Refresh -> true.also { if (idle) onRefresh() }
            MobileKeyCommand.Delete -> {
                val item = focusedItem?.takeIf { idle && it.acceptsOwnerActions && it.id.value > 0L }
                item?.let { onDelete(it, confirmedDeleteMode(confirmedTrashEnabled)) }
                item != null
            }
            else -> false
        }
    }
}

private fun <T> T?.afterFocusChange(itemId: T, focused: Boolean): T? =
    when {
        focused -> itemId
        this == itemId -> null
        else -> this
    }

@Composable
private fun MobileFilesFolderStatus(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Unit,
) {
    val operation = state.current.operation
    MobileFilesOperationStatus(operation = operation, onRetry = { onEvent(FilesBrowserEvent.Retry) })
    if (operation == FilesFolderOperation.Idle) {
        state.current.deleteOutcome?.takeUnless { it.isConfirmedTrash }?.let { outcome ->
            if (outcome.status == FilesDeleteStatus.TOO_LARGE_FOR_TRASH) {
                MobileFilesTrashLimitStatus(outcome, state.current, onEvent)
            } else {
                MobileFilesDeleteStatus(outcome)
            }
        }
        state.current.moveOutcome?.let { MobileFilesMoveStatus(it) }
    }
}

/** The Trash request succeeded and the fresh read found the item gone; a failed request proves no Trash move. */
private val FilesDeleteOutcome.isConfirmedTrash: Boolean
    get() = intent.mode == FilesDeleteMode.TRASH && failure == null &&
        status == FilesDeleteStatus.NO_LONGER_AVAILABLE

/**
 * A Trash move the fresh read confirmed is announced once, as "Moved to Trash" with Undo, which
 * restores it from Trash. Shown in full or cut short, it is then marked announced; the outcome
 * stays, so a later page that still lists the item turns it into the still-present line.
 */
@Composable
private fun MobileFilesTrashedAnnouncement(
    current: FilesFolderState,
    snackbarHostState: SnackbarHostState,
    onEvent: (FilesBrowserEvent) -> Unit,
    onUndoTrash: ((FilesDeleteOutcome) -> Unit)?,
) {
    val trashed = current.deleteOutcome?.takeIf {
        current.operation == FilesFolderOperation.Idle && it.isConfirmedTrash && !it.announced
    }
    val count = trashed?.itemCount ?: 1
    val message = if (count > 1) {
        pluralStringResource(R.plurals.mobile_files_trash_done_many, count, count)
    } else {
        stringResource(R.string.mobile_files_trash_done)
    }
    val undo = stringResource(R.string.mobile_files_undo)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentOnUndo by rememberUpdatedState(onUndoTrash)
    LaunchedEffect(trashed?.requestId) {
        val outcome = trashed ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        try {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = undo.takeIf { currentOnUndo != null },
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) currentOnUndo?.invoke(outcome)
        } finally {
            currentOnEvent(FilesBrowserEvent.DeleteOutcomeAnnounced(outcome))
        }
    }
}

/** Opens the sheet for a listed file the detail pane or the viewer asks for, while [enabled]. */
@Composable
private fun MobileFilesActionsRequests(
    fileOpening: MobileFilesOpening?,
    items: List<FilesItem>,
    enabled: Boolean,
    onOpenSheet: (FilesItemId) -> Unit,
) {
    val currentItems by rememberUpdatedState(items)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnOpenSheet by rememberUpdatedState(onOpenSheet)
    val requests = fileOpening?.actionsRequests ?: return
    LaunchedEffect(requests) {
        requests.collect { id -> if (currentEnabled && currentItems.any { it.id == id }) currentOnOpenSheet(id) }
    }
}

/** The notices alone, for a screen without its own snackbar host, such as the player. */
@Composable
internal fun MobileFilesNoticeHost(notices: Flow<MobileFilesNotice>, modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }
    MobileFilesNotices(notices, hostState)
    MobileSnackbarHost(hostState, modifier)
}

/** Item actions that did not go through, one short line each. */
@Composable
private fun MobileFilesNotices(notices: Flow<MobileFilesNotice>, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(notices) {
        notices.collect { notice ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(notice.text(context), duration = SnackbarDuration.Long)
        }
    }
}

/**
 * The friend whose folder holds the current one, by that folder's name; null outside shared items.
 * A folder an outside open pushed starts a new path, so only folders from there on are its ancestry.
 */
internal fun FilesBrowserState.sharedByName(): String? {
    val path = stack.drop(stack.indexOfLast { it.openedFrom != null }.coerceAtLeast(0))
    return path.zipWithNext().lastOrNull { (parent, child) ->
        parent.content.items().any { it.id == child.folder.id && it.folderType == PutioFolderType.SHARED_FRIEND }
    }?.second?.folder?.name
}

@Composable
private fun MobileEmptyFilesContent(
    paging: FilesPaging,
    pagingEnabled: Boolean,
    onEvent: (FilesBrowserEvent) -> Unit,
    onNewFolder: (() -> Unit)?,
) {
    LoadNextPageNow(
        nextPage = (paging as? FilesPaging.Available)?.takeIf { pagingEnabled },
        onLoadNextPage = { onEvent(FilesBrowserEvent.LoadNextPage) },
    )
    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val viewportHeight = maxHeight
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                MobileFilesEmptyState(
                    glyph = PutioEmptyGlyph.EmptyFolder,
                    headline = stringResource(R.string.mobile_files_empty_title),
                    message = stringResource(R.string.mobile_files_empty_message),
                    modifier = Modifier.heightIn(min = viewportHeight),
                    action = onNewFolder?.let { create -> { MobileNewFolderButton(onClick = create) } },
                )
            }
        }
        MobileFilesPaging(
            paging = paging,
            enabled = pagingEnabled,
            onEvent = onEvent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MobileFilesOperationStatus(
    operation: FilesFolderOperation,
    onRetry: () -> Unit,
) {
    when (operation) {
        FilesFolderOperation.Idle -> Unit
        is FilesFolderOperation.Loading -> {
            val messageResource = operation.progressMessage()
            if (messageResource != null) {
                val message = stringResource(messageResource)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .clearAndSetSemantics {},
                        strokeWidth = 2.dp,
                    )
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        is FilesFolderOperation.Failed -> {
            val message = stringResource(operation.failureMessage())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    color = PutioDesignTokens.redTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag(MOBILE_FILES_OPERATION_RETRY_TAG),
                ) {
                    Text(stringResource(operation.retryLabel()))
                }
            }
        }
    }
}

@StringRes
private fun FilesFolderOperation.Loading.progressMessage(): Int? =
    when (intent) {
        FilesFolderOperationIntent.Refresh -> null
        is FilesFolderOperationIntent.Sort -> R.string.mobile_files_sorting
        is FilesFolderOperationIntent.Rename ->
            if (phase == FilesFolderOperationPhase.RENAMING) {
                R.string.mobile_files_renaming
            } else {
                R.string.mobile_files_rename_reloading
            }
        is FilesFolderOperationIntent.Delete -> when (phase) {
            FilesFolderOperationPhase.DELETING -> R.string.mobile_files_deleting
            FilesFolderOperationPhase.CHECKING_DELETE -> R.string.mobile_files_delete_checking
            else -> R.string.mobile_files_delete_reloading
        }
        is FilesFolderOperationIntent.Move -> when (phase) {
            FilesFolderOperationPhase.MOVING -> R.string.mobile_files_moving
            FilesFolderOperationPhase.CHECKING_MOVE -> R.string.mobile_files_move_checking
            else -> R.string.mobile_files_move_reloading
        }
    }

@StringRes
private fun FilesFolderOperation.Failed.failureMessage(): Int =
    when {
        intent is FilesFolderOperationIntent.Move ->
            if (phase == FilesFolderOperationPhase.RELOADING) {
                R.string.mobile_files_move_reload_error
            } else {
                R.string.mobile_files_move_unknown
            }
        intent is FilesFolderOperationIntent.Delete ->
            if (phase == FilesFolderOperationPhase.RELOADING) {
                R.string.mobile_files_delete_reload_error
            } else {
                R.string.mobile_files_delete_unknown
            }
        intent == FilesFolderOperationIntent.Refresh -> R.string.mobile_files_refresh_error
        phase == FilesFolderOperationPhase.PERSISTING_SORT -> R.string.mobile_files_sort_error
        phase == FilesFolderOperationPhase.RENAMING -> R.string.mobile_files_rename_error
        intent is FilesFolderOperationIntent.Rename -> R.string.mobile_files_rename_reload_error
        else -> R.string.mobile_files_reload_error
    }

@StringRes
private fun FilesFolderOperation.Failed.retryLabel(): Int =
    if ((intent is FilesFolderOperationIntent.Delete || intent is FilesFolderOperationIntent.Move) &&
        phase != FilesFolderOperationPhase.RELOADING
    ) {
        R.string.mobile_files_check_status
    } else {
        R.string.mobile_action_retry
    }

@Composable
private fun MobileFilesDeleteStatus(outcome: FilesDeleteOutcome) {
    val message = outcome.statusMessage()
    if (message != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (outcome.status != FilesDeleteStatus.NO_LONGER_AVAILABLE) {
                outcome.failure?.let {
                    Text(
                        it.mobileMessage(),
                        color = PutioDesignTokens.redTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** The settled delete's line: the item by name, or several by count. */
@Composable
private fun FilesDeleteOutcome.statusMessage(): String? {
    val many = itemCount > 1
    return when (status) {
        FilesDeleteStatus.NO_LONGER_AVAILABLE -> if (many) {
            pluralStringResource(R.plurals.mobile_files_delete_unavailable_many, itemCount, itemCount)
        } else {
            stringResource(R.string.mobile_files_delete_unavailable, itemName)
        }
        FilesDeleteStatus.STILL_PRESENT -> if (many) {
            pluralStringResource(R.plurals.mobile_files_delete_still_present_many, remainingCount, remainingCount)
        } else {
            stringResource(R.string.mobile_files_delete_still_present, itemName)
        }
        FilesDeleteStatus.SKIPPED -> if (many) {
            pluralStringResource(R.plurals.mobile_files_delete_skipped_many, itemCount, itemCount)
        } else {
            stringResource(R.string.mobile_files_delete_skipped, itemName)
        }
        FilesDeleteStatus.CHECKING, FilesDeleteStatus.UNKNOWN, FilesDeleteStatus.TOO_LARGE_FOR_TRASH -> null
    }
}

@Composable
private fun MobileFilesList(
    content: FilesContent.Ready,
    headers: MobileFilesHeaders,
    onPlayEntry: (ContinueWatchingEntry) -> Unit,
    revealItemId: FilesItemId?,
    pagingEnabled: Boolean,
    onEvent: (FilesBrowserEvent) -> Unit,
    onPlayMedia: (FilesItem) -> Unit,
    onActions: (FilesItem) -> Unit,
    hasActions: (FilesItem) -> Boolean,
    operation: FilesFolderOperation,
    downloads: DownloadsState,
    onItemFocusChanged: (FilesItemId, Boolean) -> Unit,
    fileDragOut: MobileFileDragOut?,
    onDragStarted: () -> Unit,
    fileOpening: MobileFilesOpening?,
    selection: MobileFilesListSelection? = null,
) {
    val viewport = content.viewport
    // The viewport counts rows; headers sit above them and show again when the list is back at its top.
    val headerCount = headers.itemCount
    // A revealed row, the first included, scrolls past the headers to sit at the top.
    val atTop = viewport == FilesViewportPosition() && revealItemId == null
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (atTop) 0 else viewport.firstVisibleItemIndex + headerCount,
        initialFirstVisibleItemScrollOffset = viewport.firstVisibleItemScrollOffset,
    )
    val currentHeaderCount by rememberUpdatedState(headerCount)
    val keyboardFocus = rememberFilesKeyboardFocus(content.items, viewport.firstVisibleItemIndex)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val pendingItemIds = operation.pendingItemIds()
    val actionsEnabled = operation.canStartOperation

    LaunchedEffect(listState) {
        // Report only settled positions: per-frame offsets during a fling would
        // rebuild FilesBrowserState and recompose the whole signed-in tree.
        snapshotFlow {
            if (listState.isScrollInProgress) {
                null
            } else {
                val index = listState.firstVisibleItemIndex - currentHeaderCount
                FilesViewportPosition(
                    firstVisibleItemIndex = index.coerceAtLeast(0),
                    firstVisibleItemScrollOffset = if (index < 0) 0 else listState.firstVisibleItemScrollOffset,
                )
            }
        }
            .filterNotNull()
            .drop(1)
            .distinctUntilChanged()
            .collect { currentOnEvent(FilesBrowserEvent.ViewportChanged(it)) }
    }

    LoadNextPageNearEnd(
        listState = listState,
        nextPage = (content.paging as? FilesPaging.Available)?.takeIf { pagingEnabled },
        onLoadNextPage = { currentOnEvent(FilesBrowserEvent.LoadNextPage) },
    )

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(MOBILE_FILES_LIST_TAG),
        // In selection the last row scrolls clear of the floating toolbar, however tall large fonts make it.
        contentPadding = PaddingValues(bottom = selection?.bottomClearance ?: 0.dp),
    ) {
        mobileFilesHeaderItems(headers, onPlayEntry)
        itemsIndexed(
            items = content.items,
            key = { _, item -> item.id.value },
        ) { index, item ->
            MobileFilesListRow(
                item = item,
                takeEntryFocus = keyboardFocus.takesEntry(index),
                onEntryFocused = keyboardFocus::entered,
                fileDragOut = fileDragOut,
                onDragStarted = onDragStarted,
                onFocusChanged = { focused ->
                    keyboardFocus.onRowFocus(item.id, index, focused)
                    onItemFocusChanged(item.id, focused)
                },
            ) { rowModifier ->
                MobileFilesRow(
                    item = item,
                    modifier = rowModifier,
                    highlighted = item.id == revealItemId,
                    selected = item.id == fileOpening?.selectedFileId,
                    downloadStatus = downloads.rowStatus(item.id),
                    onActions = if (hasActions(item)) { { onActions(item) } } else null,
                    actionsEnabled = actionsEnabled,
                    onClick = rowClick(item, fileOpening, onEvent, onPlayMedia)
                        .takeUnless { item.id in pendingItemIds },
                    onClickLabel = rowClickLabel(item, fileOpening),
                    checked = selection?.let { item.id in it.checked },
                    onCheckedChange = selection?.takeIf { item.id.value > 0L }?.let { { it.onToggle(item) } },
                )
            }
        }
        if (content.paging != FilesPaging.Complete) {
            item(key = FILES_PAGING_ITEM_KEY) {
                MobileFilesPaging(
                    paging = content.paging,
                    enabled = pagingEnabled,
                    onEvent = onEvent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A folder opens; a file goes to [MobileFilesOpening] when there is one, else only media plays. */
private fun rowClick(
    item: FilesItem,
    fileOpening: MobileFilesOpening?,
    onEvent: (FilesBrowserEvent) -> Unit,
    onPlayMedia: (FilesItem) -> Unit,
): (() -> Unit)? =
    when {
        item.isFolder -> {
            { onEvent(FilesBrowserEvent.OpenFolder(item.id)) }
        }
        fileOpening != null && item.id.value > 0L -> {
            { fileOpening.onOpenFile(item) }
        }
        item.isPlayable -> {
            { onPlayMedia(item) }
        }
        else -> null
    }

@Composable
private fun rowClickLabel(item: FilesItem, fileOpening: MobileFilesOpening?): String? =
    when {
        item.isFolder -> stringResource(R.string.mobile_files_open_folder, item.name)
        fileOpening?.showsDetail == true -> stringResource(R.string.mobile_files_show_details, item.name)
        item.isPlayable -> stringResource(R.string.mobile_files_play_media, item.name)
        fileOpening != null -> stringResource(R.string.mobile_files_open_folder, item.name)
        else -> null
    }

/**
 * Where keyboard focus goes in a listing. A folder reached from the keyboard focuses the row last
 * focused there, such as the video that just played, else its first visible row. When the row that
 * last held focus leaves the listing (moved to Trash, say) and no row holds focus, focus goes to the
 * row now in its place; focus the viewer moved elsewhere, to the navigation rail say, stays there.
 */
@Stable
internal class FilesKeyboardFocus(
    entryIndex: Int,
    entryPending: Boolean,
    private val lastFocusedId: MutableState<Long?>,
) {
    private var entryIndex by mutableIntStateOf(entryIndex)
    private var entryPending by mutableStateOf(entryPending)
    private var focusedRow: FilesItemId? = null
    private var lastFocusedIndex: Int? = null

    fun takesEntry(index: Int): Boolean = entryPending && index == entryIndex

    fun entered() {
        entryPending = false
    }

    fun onRowFocus(id: FilesItemId, index: Int, focused: Boolean) {
        if (focused) {
            lastFocusedIndex = index
            lastFocusedId.value = id.value
        }
        focusedRow = focusedRow.afterFocusChange(id, focused)
    }

    fun afterListingChange(keyboardInput: Boolean, items: List<FilesItem>) {
        val index = lastFocusedIndex?.takeIf { keyboardInput && items.isNotEmpty() } ?: return
        val left = items.none { it.id.value == lastFocusedId.value }
        // A removed row reports no focus change, so a focused row no longer listed has lost it too.
        val rowHoldsFocus = focusedRow?.let { row -> items.any { it.id == row } } == true
        if (left && !rowHoldsFocus) {
            focusedRow = null
            entryIndex = index.coerceAtMost(items.lastIndex)
            entryPending = true
        }
    }
}

@Composable
private fun rememberFilesKeyboardFocus(items: List<FilesItem>, firstVisibleIndex: Int): FilesKeyboardFocus {
    val keyboardInput = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val lastFocusedId = rememberSaveable { mutableStateOf<Long?>(null) }
    val focus = remember {
        val entry = items.indexOfFirst { it.id.value == lastFocusedId.value }.takeIf { it >= 0 } ?: firstVisibleIndex
        FilesKeyboardFocus(entry, keyboardInput, lastFocusedId)
    }
    LaunchedEffect(items) {
        // Focus moves and drops as rows leave during this frame; decide once it has settled.
        withFrameNanos {}
        focus.afterListingChange(keyboardInput, items)
    }
    return focus
}

/**
 * A row's keyboard and pointer extras: the focus a folder entered from the keyboard puts on it, focus
 * reporting for the Delete key, and dragging a file out of the app.
 */
@Composable
private fun MobileFilesListRow(
    item: FilesItem,
    takeEntryFocus: Boolean,
    onEntryFocused: () -> Unit,
    fileDragOut: MobileFileDragOut?,
    onDragStarted: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    row: @Composable (Modifier) -> Unit,
) {
    val entryFocus = if (takeEntryFocus) remember { FocusRequester() } else null
    entryFocus?.let { requester ->
        LaunchedEffect(requester) {
            requester.requestFocus()
            onEntryFocused()
        }
    }
    val view = LocalView.current
    val density = LocalDensity.current
    val shadowBackground = MaterialTheme.colorScheme.inverseSurface.toArgb()
    val shadowForeground = MaterialTheme.colorScheme.inverseOnSurface.toArgb()
    val startDrag = rememberUpdatedState {
        val shadow =
            MobileFileDragShadow(item.name, density.density, density.fontScale, shadowBackground, shadowForeground)
        if (fileDragOut?.start(view, item, shadow) == true) onDragStarted()
    }
    row(
        Modifier
            .then(entryFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .mobileFileDragSource(fileDragOut != null && !item.isFolder && item.id.value > 0L) { startDrag.value() },
    )
}

private fun FilesFolderOperation.pendingItemIds(): Set<FilesItemId> {
    val (intent, phase) = when (this) {
        is FilesFolderOperation.Loading -> intent to phase
        is FilesFolderOperation.Failed -> intent to phase
        FilesFolderOperation.Idle -> return emptySet()
    }
    return when (intent) {
        is FilesFolderOperationIntent.Rename ->
            setOfNotNull(intent.itemId.takeIf { phase == FilesFolderOperationPhase.RELOADING })
        is FilesFolderOperationIntent.Delete -> intent.itemIds.toSet()
        is FilesFolderOperationIntent.Move -> intent.itemIds.toSet()
        else -> emptySet()
    }
}

@Composable
private fun MobileFilesPaging(
    paging: FilesPaging,
    enabled: Boolean,
    onEvent: (FilesBrowserEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (paging) {
        is FilesPaging.Available ->
            Box(
                modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    onClick = { onEvent(FilesBrowserEvent.LoadNextPage) },
                    enabled = enabled,
                    modifier = Modifier.testTag(MOBILE_FILES_PAGING_ACTION_TAG),
                ) {
                    Text(stringResource(R.string.mobile_files_load_more))
                }
            }

        is FilesPaging.Loading ->
            Row(
                modifier = modifier
                    .padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .clearAndSetSemantics {},
                    strokeWidth = 2.dp,
                )
                Text(stringResource(R.string.mobile_files_loading_more))
            }

        is FilesPaging.Failed ->
            Column(
                modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.mobile_files_paging_error),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = { onEvent(FilesBrowserEvent.Retry) },
                    enabled = enabled,
                    modifier = Modifier.testTag(MOBILE_FILES_PAGING_ACTION_TAG),
                ) {
                    Text(stringResource(R.string.mobile_action_retry))
                }
            }

        FilesPaging.Complete -> Unit
    }
}

/** put.io's reason for a refused request, else this failure's copy. */
@Composable
internal fun FilesFailure.mobileMessage(): String =
    (this as? PutioFailure)?.apiReason ?: stringResource(mobileMessageResource())

@StringRes
internal fun FilesFailure.mobileMessageResource(): Int =
    when (this) {
        FilesFailure.NavigationBlocked -> R.string.mobile_navigation_blocked
        is PutioFailure.AuthenticationRequired -> R.string.mobile_state_error_session
        is PutioFailure.AccessDenied -> R.string.mobile_state_error_forbidden
        is PutioFailure.RateLimited -> R.string.mobile_state_error_rate_limited
        is PutioFailure.ServerUnavailable -> R.string.mobile_state_error_unavailable
        is PutioFailure.NetworkUnavailable -> R.string.mobile_state_error_message
        is PutioFailure.ApiRejected,
        is PutioFailure.InvalidResponse,
        is PutioFailure.Misconfigured,
        is PutioFailure.Unexpected,
        -> R.string.mobile_state_error_unavailable
    }

private const val FILES_PAGING_ITEM_KEY = "files-paging"
