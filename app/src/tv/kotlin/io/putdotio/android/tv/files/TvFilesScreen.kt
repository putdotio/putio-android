package io.putdotio.android.tv.files

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteStatus
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.pendingIntent
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesViewportPosition
import io.putdotio.android.files.canStartOperation
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvDialog
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import io.putdotio.android.tv.TvStatusScreen
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlin.math.roundToInt

internal const val TV_FILES_LIST_TAG = "tv-files-list"
internal const val TV_FILES_ROW_TAG = "tv-files-row"

/**
 * Files per the oracle (04-06b): a title row with Refresh and Sort, then
 * full-width rows that open folders, play media, or explain an unsupported
 * type. Focus starts on the first row and the header is reachable with Up.
 * Back pops the folder stack; the shell owns Back when the stack is at root.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvFilesScreen(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onPlayMedia: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
    /** Changes with the signed-in session so one account's saved UI state never greets the next. */
    sessionKey: Any? = null,
    /** The account's confirmed `trash_enabled`; null hides deletion, since its wording depends on it. */
    confirmedTrashEnabled: Boolean? = null,
    /** True when the account keeps positions (`use_start_from`), which is what the watched toggle writes. */
    watchedToggleEnabled: Boolean = false,
    onOpenInVlc: (FilesItem) -> Unit = {},
    onSetWatched: (FilesItem, Boolean) -> Unit = { _, _ -> },
    /** An explanation from outside the browser (a failed watched write, no VLC); OK dismisses it. */
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
    /**
     * Which row last held focus in each folder; owned by the session so it survives the pane
     * being disposed for another destination. A LazyColumn is remounted on every folder
     * change and after the unsupported screen, so Compose's own restorer has no history.
     */
    focusMemory: MutableMap<Long, Long> = remember(sessionKey) { mutableMapOf() },
) {
    val current = state.current
    // Whether D-pad focus belongs to this pane. A content change while the user is on the
    // rail must not pull focus back. Only a directional exit from the pane clears it: a
    // focused row being disposed (folder change, overlay) also drops focus, but the pane is
    // still the owner and must re-request. Remembered above the unsupported overlay so
    // dismissing it does not read as a fresh entry.
    val paneHasFocus = remember { mutableStateOf(true) }
    val trackPaneFocus = Modifier
        .onFocusChanged { if (it.hasFocus) paneHasFocus.value = true }
        .focusProperties {
            exit = {
                paneHasFocus.value = false
                FocusRequester.Default
            }
        }
    var unsupported by rememberSaveable(sessionKey, current.folder.id.value) { mutableStateOf<Long?>(null) }
    val unsupportedItem = (current.content as? FilesContent.Ready)?.items?.firstOrNull { it.id.value == unsupported }
    // The overlay closes when its item leaves the listing (refresh, sort, deletion); it must
    // not come back on its own if the item reappears later.
    val overlayOrphaned = unsupported != null && unsupportedItem == null
    SideEffect { if (overlayOrphaned) unsupported = null }
    if (unsupportedItem != null) {
        val dismiss = { unsupported = null }
        BackHandler(onBack = dismiss)
        TvUnsupportedFileScreen(
            item = unsupportedItem,
            onBack = dismiss,
            claimFocus = paneHasFocus.value,
            modifier = modifier
                .then(trackPaneFocus)
                .focusGroup(),
        )
        return
    }

    // The row whose actions are up, and whether its deletion is being confirmed. Both close
    // when the row leaves the listing, like the unsupported overlay.
    var actionsFor by rememberSaveable(sessionKey, current.folder.id.value) { mutableStateOf<Long?>(null) }
    // The trash mode the user chose, kept while the confirmation is up: the dialog and the
    // event use it. A setting that changes underneath, or an operation the folder starts on
    // its own (a reload after a restore), closes the confirmation rather than offering a
    // Delete the reducer would refuse.
    var confirmingDeleteTrash by rememberSaveable(sessionKey, current.folder.id.value) { mutableStateOf<Boolean?>(null) }
    val actionsItem = (current.content as? FilesContent.Ready)?.items?.firstOrNull { it.id.value == actionsFor }
    val actionsOrphaned = actionsFor != null && actionsItem == null
    val confirmationStale = confirmingDeleteTrash != null &&
        (confirmingDeleteTrash != confirmedTrashEnabled || !current.operation.canStartOperation)
    SideEffect {
        if (actionsOrphaned) {
            actionsFor = null
            confirmingDeleteTrash = null
        } else if (confirmationStale) {
            confirmingDeleteTrash = null
        }
    }
    val dialogOpen = actionsItem != null || notice != null
    // Loading and complete-empty folders have no focusable content, so the header's Refresh
    // takes focus; a failed folder focuses its own Retry, an empty page its paging control.
    val refreshFocus = remember { FocusRequester() }
    val content = current.content
    val headerOwnsFocus = content is FilesContent.Loading ||
        (content is FilesContent.Empty && content.paging is FilesPaging.Complete)
    LaunchedEffect(current.folder.id.value, headerOwnsFocus) {
        if (headerOwnsFocus && paneHasFocus.value) refreshFocus.requestFocus()
    }
    // The shell asks the pane for focus on entry. A Column is not a target itself, so the
    // request is redirected to whichever control this pane currently wants focused.
    val retryFocus = remember { FocusRequester() }
    val pagingFocus = remember { FocusRequester() }
    val headerHasFocus = remember { mutableStateOf(false) }
    val entryTarget = remember { mutableStateOf(FocusRequester.Default) }
    entryTarget.value = when {
        headerOwnsFocus -> refreshFocus
        content is FilesContent.Failed -> retryFocus
        content is FilesContent.Empty -> pagingFocus
        content is FilesContent.Ready -> entryTarget.value
        else -> FocusRequester.Default
    }
    // A closed dialog hands focus back to the row that opened it (the pane's live entry).
    val dialogWasOpen = remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) {
        val closing = dialogWasOpen.value && !dialogOpen
        dialogWasOpen.value = dialogOpen
        if (!closing || !paneHasFocus.value) return@LaunchedEffect
        withFrameNanos {}
        if (paneHasFocus.value) entryTarget.value.requestFocus()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .focusProperties { enter = { entryTarget.value } }
            .then(trackPaneFocus)
            .focusGroup(),
    ) {
        TvFilesHeader(state, onEvent, refreshFocus, sessionKey, onFocusChanged = { headerHasFocus.value = it })
        TvFilesDeleteStatus(current, onEvent)
        when (content) {
            is FilesContent.Loading ->
                TvStatusScreen(stringResource(R.string.tv_files_loading), modifier = Modifier.weight(1f))
            is FilesContent.Failed ->
                TvStatusScreen(
                    title = stringResource(R.string.tv_error_title),
                    message = stringResource(content.failure.tvMessage()),
                    action = stringResource(R.string.tv_files_retry),
                    onAction = { onEvent(FilesBrowserEvent.Retry) },
                    modifier = Modifier.weight(1f),
                    actionFocus = retryFocus,
                    claimFocus = paneHasFocus.value,
                )
            is FilesContent.Empty ->
                Column(modifier = Modifier.weight(1f)) {
                    TvStatusScreen(stringResource(R.string.tv_files_empty), modifier = Modifier.weight(1f))
                    LaunchedEffect(content.paging is FilesPaging.Complete) {
                        if (content.paging !is FilesPaging.Complete && paneHasFocus.value) pagingFocus.requestFocus()
                    }
                    TvFilesPaging(
                        paging = content.paging,
                        enabled = current.operation.canStartOperation,
                        onEvent = onEvent,
                        buttonModifier = Modifier.focusRequester(pagingFocus),
                    )
                }
            is FilesContent.Ready ->
                TvFilesList(
                    folderId = current.folder.id.value,
                    content = content,
                    pagingEnabled = current.operation.canStartOperation,
                    focusMemory = focusMemory,
                    paneHasFocus = paneHasFocus,
                    headerHasFocus = headerHasFocus,
                    onEntryTarget = { entryTarget.value = it },
                    modifier = Modifier.weight(1f),
                    onEvent = onEvent,
                    onOpen = { item ->
                        when {
                            item.isFolder -> onEvent(FilesBrowserEvent.OpenFolder(item.id))
                            item.isPlayable -> onPlayMedia(item)
                            else -> unsupported = item.id.value
                        }
                    },
                    onActions = { item -> actionsFor = item.id.value },
                )
        }
    }

    if (notice != null) {
        TvDialog(title = notice, message = null, onDismiss = onDismissNotice) { focus ->
            TvButton(onClick = onDismissNotice, modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                Text(stringResource(R.string.tv_files_ok))
            }
        }
    } else if (actionsItem != null) {
        val close = {
            actionsFor = null
            confirmingDeleteTrash = null
        }
        val confirmTrash = confirmingDeleteTrash
        if (confirmTrash != null && !confirmationStale) {
            TvFilesDeleteDialog(
                item = actionsItem,
                trash = confirmTrash,
                onConfirm = {
                    close()
                    val mode = if (confirmTrash) FilesDeleteMode.TRASH else FilesDeleteMode.PERMANENT
                    onEvent(FilesBrowserEvent.Delete(current.folder.id, actionsItem.id, mode))
                },
                onDismiss = close,
            )
        } else {
            TvFilesActionsDialog(
                item = actionsItem,
                actions = actionsItem.tvActions(watchedToggleEnabled, confirmedTrashEnabled, current.operation.canStartOperation),
                onAction = { action ->
                    when (action) {
                        TvFilesAction.OpenInVlc -> {
                            close()
                            onOpenInVlc(actionsItem)
                        }
                        is TvFilesAction.SetWatched -> {
                            close()
                            onSetWatched(actionsItem, action.watched)
                        }
                        is TvFilesAction.Delete -> confirmingDeleteTrash = action.trash
                    }
                },
                onDismiss = close,
            )
        }
    }
}

/**
 * The shared delete operation, above the list: its phases while it runs, Check status or
 * Retry when it fails, and what the fresh listing said about the item once it settled.
 */
@Composable
private fun TvFilesDeleteStatus(
    current: FilesFolderState,
    onEvent: (FilesBrowserEvent) -> Boolean,
) {
    val operation = current.operation
    val intent = operation.pendingIntent<FilesFolderOperationIntent.Delete>()
    when {
        intent != null && operation is FilesFolderOperation.Loading -> TvFilesNotice(
            text = stringResource(
                when (operation.phase) {
                    FilesFolderOperationPhase.DELETING ->
                        if (intent.mode == FilesDeleteMode.TRASH) R.string.tv_files_deleting else R.string.tv_files_deleting_permanently
                    FilesFolderOperationPhase.CHECKING_DELETE -> R.string.tv_files_delete_checking
                    else -> R.string.tv_files_delete_reloading
                },
            ),
        )
        intent != null && operation is FilesFolderOperation.Failed -> TvFilesNotice(
            text = stringResource(
                if (operation.phase == FilesFolderOperationPhase.RELOADING) {
                    R.string.tv_files_delete_reload_error
                } else {
                    R.string.tv_files_delete_unknown
                },
            ),
            action = stringResource(
                if (operation.phase == FilesFolderOperationPhase.RELOADING) R.string.tv_files_retry else R.string.tv_files_check_status,
            ),
            onAction = { onEvent(FilesBrowserEvent.Retry) },
        )
        operation == FilesFolderOperation.Idle -> current.deleteOutcome?.let { outcome ->
            val message = when (outcome.status) {
                FilesDeleteStatus.NO_LONGER_AVAILABLE -> R.string.tv_files_delete_unavailable
                FilesDeleteStatus.STILL_PRESENT -> R.string.tv_files_delete_still_present
                FilesDeleteStatus.SKIPPED -> R.string.tv_files_delete_skipped
                FilesDeleteStatus.CHECKING, FilesDeleteStatus.UNKNOWN -> null
            }
            if (message != null) TvFilesNotice(text = stringResource(message, outcome.itemName))
        }
    }
}

/** A line above the rows, with an optional action in its own focus group so Up from a row reaches it. */
@Composable
private fun TvFilesNotice(
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) TvButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun TvFilesHeader(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    refreshFocus: FocusRequester,
    sessionKey: Any?,
    onFocusChanged: (Boolean) -> Unit,
) {
    val current = state.current
    // The buttons stay focusable while a load runs, since a disabled TV button drops focus
    // and Refresh is the one holding it when a refresh starts. Clicks are ignored locally
    // outside a loaded, idle folder; a failed folder acts only through its Retry.
    val idle = current.operation.canStartOperation &&
        current.content !is FilesContent.Loading &&
        current.content !is FilesContent.Failed
    var sorting by rememberSaveable(sessionKey, current.folder.id.value) { mutableStateOf(false) }
    // A dialog left open across a folder change or a running operation would offer choices
    // the reducer rejects, so it closes as soon as the header stops being idle.
    LaunchedEffect(idle) { if (!idle) sorting = false }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = current.folder.name ?: stringResource(R.string.tv_files_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .onFocusChanged { onFocusChanged(it.hasFocus) }
                .focusGroup(),
        ) {
            TvButton(
                onClick = { if (idle) onEvent(FilesBrowserEvent.Refresh) },
                modifier = Modifier.focusRequester(refreshFocus),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_arrow_clockwise),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tv_files_refresh))
            }
            TvButton(onClick = { if (idle) sorting = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_sort_ascending),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(current.folder.sort?.tvLabel() ?: R.string.tv_files_sort_account_default))
            }
        }
    }
    val refreshing = (current.operation as? FilesFolderOperation.Loading)
        ?.let { it.intent == FilesFolderOperationIntent.Refresh || it.intent is FilesFolderOperationIntent.Sort }
        ?: false
    if (refreshing) {
        Text(
            text = stringResource(R.string.tv_files_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    // A failed delete reports through its own status line, with Check status or Retry.
    val failed = (current.operation as? FilesFolderOperation.Failed)
        ?.takeUnless { it.intent is FilesFolderOperationIntent.Delete }
    if (failed != null) {
        val message = when {
            failed.intent == FilesFolderOperationIntent.Refresh -> R.string.tv_files_refresh_error
            failed.phase == FilesFolderOperationPhase.PERSISTING_SORT -> R.string.tv_files_sort_error
            else -> R.string.tv_files_reload_error
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TvButton(onClick = { onEvent(FilesBrowserEvent.Retry) }) {
                Text(stringResource(R.string.tv_files_retry))
            }
        }
    }
    if (sorting) {
        TvSortDialog(
            selected = current.folder.sort,
            onSelect = { sort ->
                sorting = false
                onEvent(FilesBrowserEvent.SelectSort(sort))
            },
            onDismiss = { sorting = false },
        )
    }
}

@Composable
private fun TvFilesList(
    folderId: Long,
    content: FilesContent.Ready,
    pagingEnabled: Boolean,
    focusMemory: MutableMap<Long, Long>,
    paneHasFocus: State<Boolean>,
    headerHasFocus: State<Boolean>,
    onEntryTarget: (FocusRequester) -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onOpen: (FilesItem) -> Unit,
    onActions: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewport = content.viewport
    val listState = key(folderId) {
        rememberLazyListState(viewport.firstVisibleItemIndex, viewport.firstVisibleItemScrollOffset)
    }
    val currentOnEvent by rememberUpdatedState(onEvent)
    val remembered = focusMemory[folderId]
    val resumeOnPaging = remembered == PAGING_FOCUS_MARKER && content.paging != FilesPaging.Complete
    val rowFocus = remember(listState) { FocusRequester() }
    // Follows whichever row holds focus, so re-entering the pane from the rail lands on
    // the live owner rather than the row that was focused at mount.
    var focusedRowId by remember(listState) { mutableStateOf<Long?>(null) }
    val liveRowFocus = remember(listState) { FocusRequester() }
    val listPagingFocus = remember(listState) { FocusRequester() }
    // Set when the paging control gains focus and cleared only when a row does, so the
    // control losing focus by being disposed does not erase the fact that it had it.
    val pagingHeldFocus = remember(listState) { mutableStateOf(resumeOnPaging) }
    // A sort or refresh swaps the rows without remounting the list; the row that held focus
    // may have moved off screen or gone, so the live requester is only trusted while it is
    // attached to a row that is still listed.
    val listedIds = remember(content.items) { content.items.mapTo(HashSet()) { it.id.value } }
    val liveRowListed = focusedRowId != null && focusedRowId in listedIds
    // A listing change refocuses a row only when a row was what the user was on. A row being
    // disposed by that change also reports losing focus, so ownership is inferred from the
    // other in-pane owners instead: the header controls and the paging latch.
    val rowOwnsFocus = focusedRowId != null && !headerHasFocus.value && !pagingHeldFocus.value
    val entryFocus = when {
        pagingHeldFocus.value && content.paging != FilesPaging.Complete -> listPagingFocus
        !liveRowListed -> rowFocus
        else -> liveRowFocus
    }
    SideEffect { onEntryTarget(entryFocus) }
    // The paging control leaves the list when the last page lands; if it held focus, the
    // last row becomes the remembered row so the restorer's fallback lands there.
    // Decided during composition: once the paging item is gone the list's restorer moves
    // focus to an earlier row and clears the latch before any effect could read it.
    val handOffToLastRow = remember(listState) { mutableStateOf(false) }
    if (content.paging == FilesPaging.Complete && pagingHeldFocus.value) {
        val lastId = content.items.last().id.value
        focusMemory[folderId] = lastId
        focusedRowId = lastId
        handOffToLastRow.value = true
        pagingHeldFocus.value = false
    }
    // On every mount the remembered row for this folder takes focus, or the first row the
    // first time in. The row is scrolled into view first, since a lazy row that is not
    // composed has no focus requester to answer.
    // The paging control that held focus is gone if the last page landed while the pane was
    // away; the row that replaced it is the last one.
    val focusTarget = when {
        remembered == PAGING_FOCUS_MARKER && content.paging == FilesPaging.Complete -> content.items.last().id.value
        else -> content.items.firstOrNull { it.id.value == remembered }?.id?.value ?: content.items.first().id.value
    }
    // After the rows change under a mounted list, keep focus on the row that had it, or on
    // the fallback target when that row is gone.
    LaunchedEffect(content.items) {
        if (!rowOwnsFocus) return@LaunchedEffect
        val liveId = focusedRowId ?: return@LaunchedEffect
        val index = content.items.indexOfFirst { it.id.value == liveId }
        val (targetId, requester) = if (index >= 0) liveId to liveRowFocus else focusTarget to rowFocus
        if (index < 0) focusedRowId = null
        val targetIndex = content.items.indexOfFirst { it.id.value == targetId }
        if (targetIndex >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.key == targetId }) {
            listState.scrollToItem(targetIndex)
        }
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == targetId } }.first { it }
        withFrameNanos {}
        if (paneHasFocus.value) requester.requestFocus()
    }
    LaunchedEffect(handOffToLastRow.value) {
        if (!handOffToLastRow.value) return@LaunchedEffect
        val lastId = content.items.last().id.value
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == lastId }) {
            listState.scrollToItem(content.items.lastIndex)
        }
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == lastId } }.first { it }
        // The row is always scrolled into view so the rail's enter target is composed; the
        // restorer answers the removed node first, so the request goes out after that frame,
        // and only if the user has not left for the rail during the wait.
        withFrameNanos {}
        if (paneHasFocus.value) liveRowFocus.requestFocus()
        handOffToLastRow.value = false
    }
    LaunchedEffect(listState) {
        val index = content.items.indexOfFirst { it.id.value == focusTarget }
        // A list mounted while the user is on the rail (Loading → Ready after Left) keeps its
        // entry target ready but does not take focus; the enter redirect delivers it later.
        // Read after the layout waits, since the user may leave for the rail during them.
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.key == focusTarget }) {
            listState.scrollToItem(index)
        }
        if (resumeOnPaging) {
            listState.scrollToItem(content.items.size)
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == TV_FILES_PAGING_KEY } }.first { it }
            withFrameNanos {}
            if (paneHasFocus.value) listPagingFocus.requestFocus()
        } else {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == focusTarget } }.first { it }
            // The shell's pane request lands one frame earlier; this one settles on the row.
            withFrameNanos {}
            if (paneHasFocus.value) rowFocus.requestFocus()
        }
        // Started after the programmatic scroll so the settled position it reports is the one
        // on screen. Only the mount position is skipped: it came from the reducer already.
        var reported = viewport
        snapshotFlow {
            if (listState.isScrollInProgress) {
                null
            } else {
                FilesViewportPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
        }
            .filterNotNull()
            .collect {
                if (it != reported) {
                    reported = it
                    currentOnEvent(FilesBrowserEvent.ViewportChanged(it))
                }
            }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .focusRestorer(entryFocus)
            .focusGroup()
            .testTag(TV_FILES_LIST_TAG),
    ) {
        items(items = content.items, key = { it.id.value }) { item ->
            TvFilesRow(
                item = item,
                onClick = { onOpen(item) },
                onActions = { onActions(item) },
                modifier = Modifier
                    .then(if (item.id.value == focusTarget) Modifier.focusRequester(rowFocus) else Modifier)
                    .then(if (item.id.value == focusedRowId) Modifier.focusRequester(liveRowFocus) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusMemory[folderId] = item.id.value
                            focusedRowId = item.id.value
                            pagingHeldFocus.value = false
                        }
                    },
            )
        }
        if (content.paging != FilesPaging.Complete) {
            item(key = TV_FILES_PAGING_KEY) {
                TvFilesPaging(
                    paging = content.paging,
                    enabled = pagingEnabled,
                    onEvent = onEvent,
                    buttonModifier = Modifier
                        .focusRequester(listPagingFocus)
                        .onFocusChanged {
                            if (it.isFocused) {
                                pagingHeldFocus.value = true
                                focusMemory[folderId] = PAGING_FOCUS_MARKER
                            }
                        },
                )
            }
        }
    }
}

@Composable
internal fun TvFilesRow(
    item: FilesItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Long-press Center or the Menu key; null when the row offers nothing beyond opening. */
    onActions: (() -> Unit)? = null,
    /** What Center does here, when it is not the Files default of open, play, or explain. */
    label: String = when {
        item.isFolder -> stringResource(R.string.tv_files_open_folder, item.name)
        item.isPlayable -> stringResource(R.string.tv_files_play_media, item.name)
        else -> item.name
    },
) {
    val context = LocalContext.current
    val size = remember(context, item.sizeBytes) {
        Formatter.formatShortFileSize(context, item.sizeBytes.coerceAtLeast(0L))
    }
    ListItem(
        selected = false,
        onClick = onClick,
        onLongClick = onActions,
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(size, maxLines = 1)
                item.playback?.takeIf { it.isWatched }?.let { TvWatchedIndicator(it) }
            }
        },
        leadingContent = {
            Icon(
                painter = painterResource(fileTypeIconRes(item.type)),
                contentDescription = null,
                tint = PutioDesignTokens.yellowSolid,
                modifier = Modifier.size(ListItemDefaults.IconSize),
            )
        },
        trailingContent = if (item.isFolder) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_caret_right),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            null
        },
        scale = ListItemDefaults.scale(focusedScale = FULL_WIDTH_FOCUSED_SCALE),
        modifier = modifier
            .fillMaxWidth()
            // Remotes with a Menu key open the actions without holding Center.
            .onKeyEvent { event ->
                if (onActions != null && event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                    onActions()
                    true
                } else {
                    false
                }
            }
            .semantics { contentDescription = label }
            .testTag(TV_FILES_ROW_TAG),
    )
}

@Composable
private fun TvWatchedIndicator(progress: FilesPlaybackProgress) {
    val fraction = progress.fraction
    val label = if (fraction == null) {
        stringResource(R.string.tv_files_watched)
    } else {
        stringResource(R.string.tv_files_watched_percent, (fraction * PERCENT_SCALE).roundToInt())
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fraction != null) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clearAndSetSemantics {},
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TvFilesPaging(
    paging: FilesPaging,
    enabled: Boolean,
    onEvent: (FilesBrowserEvent) -> Boolean,
    buttonModifier: Modifier = Modifier,
) {
    // One button across every paging phase, so the node that holds focus survives the
    // transition from Load more to loading to a retry.
    val label = when (paging) {
        is FilesPaging.Available -> R.string.tv_files_load_more
        is FilesPaging.Loading -> R.string.tv_files_loading_more
        is FilesPaging.Failed -> R.string.tv_files_retry
        FilesPaging.Complete -> null
    } ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paging is FilesPaging.Failed) {
            Text(stringResource(R.string.tv_files_paging_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TvButton(
            onClick = {
                when (paging) {
                    is FilesPaging.Available -> if (enabled) onEvent(FilesBrowserEvent.LoadNextPage)
                    is FilesPaging.Failed -> if (enabled) onEvent(FilesBrowserEvent.Retry)
                    is FilesPaging.Loading, FilesPaging.Complete -> Unit
                }
            },
            modifier = buttonModifier,
        ) {
            Text(stringResource(label))
        }
    }
}

@Composable
private fun TvUnsupportedFileScreen(
    item: FilesItem,
    onBack: () -> Unit,
    claimFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_x_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(56.dp),
            )
            Text(
                text = stringResource(R.string.tv_files_unsupported_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = stringResource(R.string.tv_files_unsupported_message),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            val backFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { if (claimFocus) backFocus.requestFocus() }
            TvButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(top = 40.dp)
                    .focusRequester(backFocus),
            ) {
                Text(stringResource(R.string.tv_files_go_back))
            }
        }
    }
}

private const val TV_FILES_PAGING_KEY = "tv-files-paging"

/** Item ids are positive, so this marks "the paging control" in the per-folder focus memory. */
private const val PAGING_FOCUS_MARKER = -1L
private const val FULL_WIDTH_FOCUSED_SCALE = 1.02f
private const val PERCENT_SCALE = 100f
