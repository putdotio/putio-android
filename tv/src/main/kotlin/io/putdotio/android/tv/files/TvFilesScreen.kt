package io.putdotio.android.tv.files

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.FilesDeleteStatus
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.pendingIntent
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.canStartOperation
import io.putdotio.android.files.items
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.tv.ROW_BLEED
import io.putdotio.android.tv.TvListDetail
import io.putdotio.android.tv.TvPlayStart
import io.putdotio.android.tv.monoNumber
import io.putdotio.android.tv.TvSectionLine
import io.putdotio.android.tv.TvSectionPill
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.rememberTvPaneHasFocus
import io.putdotio.android.tv.TvEmptyState
import io.putdotio.android.tv.TvListSkeleton
import io.putdotio.android.tv.TvDetailSkeleton
import io.putdotio.android.tv.TvLoadFailedState
import io.putdotio.android.tv.TV_LIST_WIDTH
import androidx.compose.foundation.layout.offset
import io.putdotio.android.tv.TvNoticeDialog
import io.putdotio.android.tv.TvConfirmDialog
import io.putdotio.android.tv.TvButtonSize
import io.putdotio.android.tv.TvButtonStyle
import io.putdotio.android.tv.TvSnackbarHost
import io.putdotio.android.tv.TvSnackbarMessage
import io.putdotio.android.tv.TvSnackbarState
import io.putdotio.android.files.FilesItemId
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import io.putdotio.android.tv.returnDestination
import io.putdotio.android.tv.refocusAfterDialog

internal const val TV_FILES_LIST_TAG = "tv-files-list"

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
    pinnedIds: List<FilesItemId>? = null,
    onSetPinned: (FilesItem, Boolean) -> Unit = { _, _ -> },
    onPickSubtitles: (FilesItem, TvPlayStart) -> Unit = { _, _ -> },
    onUndoTrash: ((FilesDeleteOutcome) -> Unit)? = null,
    /** An explanation from outside the browser (a failed watched write, no VLC); OK dismisses it. */
    notice: String? = null,
    noticeMessage: String? = null,
    onDismissNotice: () -> Unit = {},
    /**
     * Which row last held focus in each folder; owned by the session so it survives the pane
     * being disposed for another destination. A LazyColumn is remounted on every folder
     * change and after the unsupported screen, so Compose's own restorer has no history.
     */
    focusMemory: MutableMap<Long, Long> = remember(sessionKey) { mutableMapOf() },
    onPlay: (FilesItem, TvPlayStart) -> Unit = { item, _ -> onPlayMedia(item) },
    resumePlayback: Boolean = true,
    detailSources: TvFilesDetailSources = remember(sessionKey) { TvFilesDetailSources.None },
) {
    val current = state.current
    // Whether D-pad focus belongs to this pane. A content change while the user is on the
    // rail must not pull focus back. Only a directional exit from the pane clears it: a
    // focused row being disposed (folder change, overlay) also drops focus, but the pane is
    // still the owner and must re-request. Remembered above the unsupported overlay so
    // dismissing it does not read as a fresh entry.
    val paneHasFocus = rememberTvPaneHasFocus()
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

    // The row whose actions are up, and whether its permanent deletion is being confirmed.
    // Both close when the row leaves the listing, like the unsupported overlay.
    var actionsFor by rememberSaveable(sessionKey, current.folder.id.value) { mutableStateOf<Long?>(null) }
    // Only permanent deletion confirms; Move to trash runs at once, as on web and iOS. Trash
    // turning on underneath, or an operation the folder starts on its own (a reload after a
    // restore), closes the confirmation rather than offering a Delete the reducer would refuse.
    var confirmingPermanentDelete by rememberSaveable(sessionKey, current.folder.id.value) {
        mutableStateOf(false)
    }
    // A Move to trash press is sent after the next composition, against the latest Trash setting:
    // a press made before a change to the setting reached the menu must not send TRASH, which
    // the server applies as permanent deletion once Trash is off.
    var trashPressed by remember(sessionKey, current.folder.id.value) { mutableStateOf(false) }
    val actionsItem = (current.content as? FilesContent.Ready)?.items?.firstOrNull { it.id.value == actionsFor }
    val actionsOrphaned = actionsFor != null && actionsItem == null
    val confirmationStale = confirmingPermanentDelete && !current.permanentDeleteConfirmable(confirmedTrashEnabled)
    SideEffect {
        if (actionsOrphaned) {
            actionsFor = null
            confirmingPermanentDelete = false
            trashPressed = false
        } else if (confirmationStale) {
            confirmingPermanentDelete = false
        }
    }
    val dialogOpen = actionsItem != null || notice != null
    val sortFocus = remember { FocusRequester() }
    val content = current.content
    // The shell asks the pane for focus on entry. A Column is not a target itself, so the
    // request is redirected to whichever control this pane currently wants focused.
    val retryFocus = remember { FocusRequester() }
    val pagingFocus = remember { FocusRequester() }
    val headerHasFocus = remember { mutableStateOf(false) }
    val entryTarget = remember { mutableStateOf(FocusRequester.Default) }
    val listEntry = remember { mutableStateOf(FocusRequester.Default) }
    val skeletonFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    entryTarget.value = content.paneEntryTarget(skeletonFocus, retryFocus, pagingFocus, emptyFocus, listEntry)
    val folderId = current.folder.id.value
    var detailId by remember(sessionKey, folderId) { mutableStateOf(focusMemory[folderId]) }
    val detailItem = content.detailItem(detailId, focusMemory[folderId])
    val detailFocus = remember { TvFilesDetailFocus() }
    val listing = rememberTvFilesListing(current)
    val actionContext = TvFilesActionContext(
        watchedToggleEnabled = watchedToggleEnabled,
        trashEnabled = confirmedTrashEnabled,
        canDelete = current.operation.canStartOperation,
        resumePlayback = resumePlayback,
    )
    val actionContextOf = { item: FilesItem -> actionContext.copy(pinned = pinnedIds?.let { item.id in it }) }
    val actionsOf = { item: FilesItem -> item.tvActions(actionContextOf(item)) }
    val dialogReturn = remember { mutableStateOf(FocusRequester.Default) }
    dialogReturn.value = detailFocus.dialogReturn(detailItem.offersActions(actionsOf), entryTarget.value)
    val dialogWasOpen = remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) { refocusAfterDialog(dialogOpen, dialogWasOpen, paneHasFocus, dialogReturn) }
    // As in tv-native, a row with nothing to offer opens no menu.
    val openActions = { item: FilesItem -> if (actionsOf(item).isNotEmpty()) actionsFor = item.id.value }
    val open = { item: FilesItem -> item.open(onEvent, onPlayMedia, onUnsupported = { unsupported = it.id.value }) }
    val snackbar = remember(sessionKey) { TvSnackbarState() }
    TvFilesTrashedAnnouncement(current, snackbar, onEvent, onUndoTrash)
    var undoFocused by remember { mutableStateOf(false) }
    val snackbarShowing = snackbar.showing
    LaunchedEffect(snackbarShowing) {
        if (snackbarShowing == null && undoFocused) {
            undoFocused = false
            withFrameNanos {}
            entryTarget.value.requestFocus()
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .focusProperties { enter = { entryTarget.value } }
            .then(trackPaneFocus)
            .focusGroup(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (content.fillsPane()) {
                TvFilesStatePane(
                    state = state,
                    onEvent = onEvent,
                    paneHasFocus = paneHasFocus,
                    retryFocus = retryFocus,
                    pagingFocus = pagingFocus,
                    emptyFocus = emptyFocus,
                )
            } else {
                TvFilesFolderHeader(state)
                TvListDetail(
                    modifier = Modifier.weight(1f),
                    detailFocus = detailFocus.primary,
                    listFocus = listEntry.value,
                    onDetailFocusChanged = detailFocus::onDetailFocusChanged,
                    list = {
                        TvFilesSectionLine(
                            state = state,
                            onEvent = onEvent,
                            sortFocus = sortFocus,
                            sessionKey = sessionKey,
                            showSort = content !is FilesContent.Loading,
                            onFocusChanged = {
                                headerHasFocus.value = it
                                detailFocus.onListFocusChanged(it)
                            },
                        )
                        TvFilesOperationStatus(current, onEvent)
                        TvFilesDeleteStatus(current, onEvent)
                        TvFilesRows(
                            current = current,
                            focusMemory = focusMemory,
                            paneHasFocus = paneHasFocus,
                            headerHasFocus = headerHasFocus,
                            detailOwnsFocus = detailFocus.owns,
                            skeletonFocus = skeletonFocus,
                            onEntryTarget = { listEntry.value = it },
                            modifier = Modifier.weight(1f),
                            onEvent = onEvent,
                            onOpen = open,
                            onActions = openActions,
                            onRowFocused = {
                                detailId = it.id.value
                                detailFocus.onListFocusChanged(true)
                            },
                        )
                    },
                    detail = {
                        TvFilesDetailPane(
                            loading = content is FilesContent.Loading,
                            item = detailItem,
                            detailFocus = detailFocus,
                            resumePlayback = resumePlayback,
                            sources = detailSources,
                            listing = listing,
                            onOpenFolder = open,
                            onPlay = onPlay,
                            onUnsupported = { unsupported = it.id.value },
                            onMore = openActions,
                            offersMore = { actionsOf(it).isNotEmpty() },
                        )
                    },
                )
            }
        }
        TvSnackbarHost(
            state = snackbar,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = SNACKBAR_BOTTOM),
            actionSize = TvButtonSize.Snackbar,
            actionModifier = Modifier.onFocusChanged { if (snackbar.showing != null) undoFocused = it.isFocused },
        )
    }

    if (notice != null) {
        TvNoticeDialog(
            title = notice,
            message = noticeMessage,
            onDismiss = onDismissNotice,
            okStyle = TvButtonStyle.Filled,
            balanceMessage = true,
        )
    } else if (actionsItem != null) {
        TvFilesActionsMenu(
            item = actionsItem,
            folder = current,
            context = actionContextOf(actionsItem),
            confirmingPermanentDelete = confirmingPermanentDelete,
            onConfirmPermanentDelete = { confirmingPermanentDelete = true },
            trashPressed = trashPressed,
            onTrashPressedChange = { trashPressed = it },
            onEvent = onEvent,
            handlers = TvFilesActionHandlers(
                onPlay = onPlay,
                onPickSubtitles = onPickSubtitles,
                onOpenInVlc = onOpenInVlc,
                onSetWatched = onSetWatched,
                onSetPinned = onSetPinned,
            ),
            onClose = {
                actionsFor = null
                confirmingPermanentDelete = false
                trashPressed = false
            },
        )
    }
}

@Composable
private fun TvFilesFolderHeader(state: FilesBrowserState) {
    val folderName = state.current.folder.name
    if (folderName != null) {
        Box(modifier = Modifier.height(FOLDER_HEADER_HEIGHT)) {
            TvFolderHeading(folderName, crumb = state.crumbLabel())
        }
    } else {
        Spacer(Modifier.height(ROOT_TOP_GAP))
    }
}

@Composable
private fun TvFolderHeading(name: String, crumb: String) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_arrow_left),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = crumb,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 27.sp, lineHeight = 31.sp),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun FilesBrowserState.crumbLabel(): String =
    current.openedFrom.returnDestination()?.let { stringResource(it.label) }
        ?: stack.dropLast(1).lastOrNull()?.folder?.name
        ?: stringResource(R.string.tv_files_title)

private fun FilesContent.detailItem(focusedId: Long?, rememberedId: Long?): FilesItem? =
    (this as? FilesContent.Ready)?.items?.let { items ->
        items.firstOrNull { it.id.value == focusedId }
            ?: items.firstOrNull { it.id.value == rememberedId }
            ?: items.first()
    }

private fun FilesItem.open(
    onEvent: (FilesBrowserEvent) -> Boolean,
    onPlayMedia: (FilesItem) -> Unit,
    onUnsupported: (FilesItem) -> Unit,
) {
    when {
        isFolder -> onEvent(FilesBrowserEvent.OpenFolder(id))
        isPlayable -> onPlayMedia(this)
        else -> onUnsupported(this)
    }
}

/** For a listing, the target is the row the list last reported in [listEntry]. */
private fun FilesContent.paneEntryTarget(
    skeleton: FocusRequester,
    retry: FocusRequester,
    pagingControl: FocusRequester,
    emptyState: FocusRequester,
    listEntry: State<FocusRequester>,
): FocusRequester =
    when (this) {
        is FilesContent.Loading -> skeleton
        is FilesContent.Failed -> retry
        is FilesContent.Empty -> if (paging is FilesPaging.Complete) emptyState else pagingControl
        is FilesContent.Ready -> listEntry.value
    }

private fun FilesContent.fillsPane(): Boolean = this is FilesContent.Failed || this is FilesContent.Empty

@Composable
private fun TvFilesRows(
    current: FilesFolderState,
    focusMemory: MutableMap<Long, Long>,
    paneHasFocus: State<Boolean>,
    headerHasFocus: State<Boolean>,
    detailOwnsFocus: State<Boolean>,
    skeletonFocus: FocusRequester,
    onEntryTarget: (FocusRequester) -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onOpen: (FilesItem) -> Unit,
    onActions: (FilesItem) -> Unit,
    onRowFocused: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val folderId = current.folder.id.value
    when (val content = current.content) {
        is FilesContent.Ready -> TvFilesList(
            folderId = folderId,
            content = content,
            pagingEnabled = current.operation.canStartOperation,
            focusMemory = focusMemory,
            paneHasFocus = paneHasFocus,
            headerHasFocus = headerHasFocus,
            detailOwnsFocus = detailOwnsFocus,
            onEntryTarget = onEntryTarget,
            modifier = modifier,
            onEvent = onEvent,
            onOpen = onOpen,
            onActions = onActions,
            onRowFocused = onRowFocused,
        )
        is FilesContent.Loading -> {
            LaunchedEffect(folderId) { if (paneHasFocus.value) skeletonFocus.requestFocus() }
            TvListSkeleton(modifier.focusRequester(skeletonFocus).focusable())
        }
        is FilesContent.Empty, is FilesContent.Failed -> Unit
    }
}

@Composable
private fun ColumnScope.TvFilesDetailPane(
    loading: Boolean,
    item: FilesItem?,
    detailFocus: TvFilesDetailFocus,
    resumePlayback: Boolean,
    sources: TvFilesDetailSources,
    listing: Any,
    onOpenFolder: (FilesItem) -> Unit,
    onPlay: (FilesItem, TvPlayStart) -> Unit,
    onUnsupported: (FilesItem) -> Unit,
    onMore: (FilesItem) -> Unit,
    offersMore: (FilesItem) -> Boolean,
) {
    if (loading) TvDetailSkeleton()
    if (item == null) return
    TvFilesDetail(
        item = item,
        detailHasFocus = detailFocus.hasFocus,
        primaryFocus = detailFocus.primary,
        resumePlayback = resumePlayback,
        sources = sources,
        listing = listing,
        actions = TvFilesDetailActions(
            onOpenFolder = onOpenFolder,
            onPlay = onPlay,
            onUnsupported = onUnsupported,
            onMore = onMore.takeIf { offersMore(item) },
        ),
        moreFocus = detailFocus.more,
    )
}

@Composable
private fun TvFilesStatePane(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    paneHasFocus: State<Boolean>,
    retryFocus: FocusRequester,
    pagingFocus: FocusRequester,
    emptyFocus: FocusRequester,
) {
    val current = state.current
    val stateModifier = Modifier
        .fillMaxSize()
        .padding(
            top = if (current.folder.name == null) ROOT_TOP_GAP else FOLDER_STATE_TOP,
            bottom = STATE_BOTTOM_GAP,
        )
    Box(modifier = Modifier.fillMaxSize()) {
        Column {
            TvFilesFolderHeader(state)
            Column(modifier = Modifier.offset(x = -ROW_BLEED).width(TV_LIST_WIDTH)) {
                TvFilesOperationStatus(current, onEvent)
                TvFilesDeleteStatus(current, onEvent)
            }
        }
        when (val content = current.content) {
            is FilesContent.Failed -> TvLoadFailedState(
                failure = content.failure,
                headline = stringResource(R.string.tv_files_error_title),
                message = content.failure.tvLoadFailedMessage(),
                onRetry = { onEvent(FilesBrowserEvent.Retry) },
                modifier = stateModifier,
                actionFocus = retryFocus,
                claimFocus = paneHasFocus.value,
            )
            is FilesContent.Empty -> Column(modifier = stateModifier) {
                val complete = content.paging is FilesPaging.Complete
                TvEmptyState(
                    glyph = PutioEmptyGlyph.EmptyFolder,
                    headline = stringResource(R.string.tv_files_empty_title),
                    message = stringResource(R.string.tv_files_empty_message),
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (complete) {
                                Modifier
                                    .focusRequester(emptyFocus)
                                    .semantics(mergeDescendants = true) {}
                                    .focusable()
                            } else {
                                Modifier
                            },
                        ),
                )
                LaunchedEffect(complete) {
                    if (paneHasFocus.value) (if (complete) emptyFocus else pagingFocus).requestFocus()
                }
                TvFilesPaging(
                    paging = content.paging,
                    enabled = current.operation.canStartOperation,
                    onEvent = onEvent,
                    buttonModifier = Modifier.focusRequester(pagingFocus),
                )
            }
            is FilesContent.Loading, is FilesContent.Ready -> Unit
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
            text = stringResource(operation.phase.tvDeleteProgress(intent.mode)),
        )
        intent != null && operation is FilesFolderOperation.Failed -> {
            val reloading = operation.phase == FilesFolderOperationPhase.RELOADING
            TvFilesNotice(
                text = stringResource(
                    if (reloading) R.string.tv_files_delete_reload_error else R.string.tv_files_delete_unknown,
                ),
                action = stringResource(if (reloading) R.string.tv_files_retry else R.string.tv_files_check_status),
                onAction = { onEvent(FilesBrowserEvent.Retry) },
            )
        }
        operation == FilesFolderOperation.Idle &&
            current.deleteOutcome?.status == FilesDeleteStatus.TOO_LARGE_FOR_TRASH ->
            TvFilesTrashLimitStatus(checkNotNull(current.deleteOutcome), current, onEvent)
        operation == FilesFolderOperation.Idle ->
            current.deleteOutcome?.takeUnless { it.isConfirmedTrash }?.let { outcome ->
                val message = outcome.status.tvOutcomeMessage()
                if (message != null) TvFilesNotice(text = stringResource(message, outcome.itemName))
            }
    }
}

@StringRes
private fun FilesFolderOperationPhase.tvDeleteProgress(mode: FilesDeleteMode): Int =
    when (this) {
        FilesFolderOperationPhase.DELETING ->
            if (mode == FilesDeleteMode.TRASH) {
                R.string.tv_files_deleting
            } else {
                R.string.tv_files_deleting_permanently
            }
        FilesFolderOperationPhase.CHECKING_DELETE -> R.string.tv_files_delete_checking
        else -> R.string.tv_files_delete_reloading
    }

@StringRes
private fun FilesDeleteStatus.tvOutcomeMessage(): Int? =
    when (this) {
        FilesDeleteStatus.NO_LONGER_AVAILABLE -> R.string.tv_files_delete_unavailable
        FilesDeleteStatus.STILL_PRESENT -> R.string.tv_files_delete_still_present
        FilesDeleteStatus.SKIPPED -> R.string.tv_files_delete_skipped
        FilesDeleteStatus.CHECKING, FilesDeleteStatus.UNKNOWN, FilesDeleteStatus.TOO_LARGE_FOR_TRASH -> null
    }

/**
 * A folder put.io would not send to trash stays with web's explanation and a Delete permanently
 * action; only the confirmation dialog, which opens with Cancel focused, sends the permanent delete.
 */
@Composable
private fun TvFilesTrashLimitStatus(
    outcome: FilesDeleteOutcome,
    current: FilesFolderState,
    onEvent: (FilesBrowserEvent) -> Boolean,
) {
    var confirming by rememberSaveable(outcome.requestId.value) { mutableStateOf(false) }
    // A refresh that no longer lists the folder leaves nothing for the reducer to delete; a
    // rename keeps the outcome, so the dialog names the folder as it is listed now.
    val listed = current.content.items().firstOrNull { it.id in outcome.intent.itemIds }
    TvFilesNotice(
        text = stringResource(R.string.tv_files_trash_limit_title),
        action = stringResource(R.string.tv_files_action_delete).takeIf { listed != null },
        onAction = { confirming = true },
    )
    if (confirming && listed != null) {
        val close = { confirming = false }
        val delete = FilesBrowserEvent.Delete(current.folder.id, listed.id, FilesDeleteMode.PERMANENT)
        TvConfirmDialog(
            title = stringResource(R.string.tv_files_trash_limit_title),
            message = stringResource(R.string.tv_files_trash_limit_message, listed.name),
            confirmLabel = stringResource(R.string.tv_files_delete_confirm),
            onConfirm = {
                close()
                onEvent(delete)
            },
            onDismiss = close,
            destructive = true,
            balanceMessage = true,
        )
    }
}

/** A line above the rows, with an optional action in its own focus group so Up from a row reaches it. */
@Composable
private fun TvFilesNotice(
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
    error: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ROW_BLEED, end = ROW_BLEED, bottom = 8.dp)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) TvButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun TvFilesSectionLine(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    sortFocus: FocusRequester,
    sessionKey: Any?,
    showSort: Boolean,
    onFocusChanged: (Boolean) -> Unit,
) {
    val current = state.current
    val idle = current.operation.canStartOperation &&
        current.content !is FilesContent.Loading &&
        current.content !is FilesContent.Failed
    var sorting by rememberSaveable(sessionKey, current.folder.id.value) { mutableStateOf(false) }
    LaunchedEffect(idle) { if (!idle) sorting = false }
    TvSectionLine(
        modifier = Modifier.onFocusChanged { onFocusChanged(it.hasFocus) },
        pill = if (showSort) {
            {
                TvSectionPill(
                    icon = R.drawable.ic_ph_sort_ascending,
                    label = stringResource(current.folder.sort?.tvLabel() ?: R.string.tv_files_sort_account_default),
                    onClick = { if (idle) sorting = true },
                    modifier = Modifier.focusRequester(sortFocus),
                    showFocus = !sorting,
                )
            }
        } else {
            null
        },
    ) {
        val summary = current.summary
        if (current.folder.name == null) {
            Text(
                stringResource(R.string.tv_files_all_files),
                style = SECTION_LABEL,
                modifier = if (showSort) Modifier else Modifier.align(Alignment.Top),
            )
        } else if (summary != null) {
            val count = summary.itemCount.toString()
            val label = pluralStringResource(R.plurals.tv_files_item_count, summary.itemCount, count)
            Text(monoNumber(label, count), style = SECTION_LABEL)
            summary.sizeBytes?.let { Text(formatFileSize(it), style = SECTION_LABEL_MONO) }
        }
    }
    if (sorting) {
        TvSortSheet(
            selected = current.folder.sort,
            onSelect = { sort ->
                sorting = false
                onEvent(FilesBrowserEvent.SelectSort(sort))
            },
            onDismiss = { sorting = false },
            returnFocus = sortFocus,
        )
    }
}

@Composable
private fun TvFilesOperationStatus(
    current: FilesFolderState,
    onEvent: (FilesBrowserEvent) -> Boolean,
) {
    if (current.operation.reloadsListing()) {
        TvFilesNotice(text = stringResource(R.string.tv_state_loading))
    }
    val failed = (current.operation as? FilesFolderOperation.Failed)
        ?.takeUnless { it.intent is FilesFolderOperationIntent.Delete }
    if (failed != null) {
        TvFilesNotice(
            text = stringResource(failed.tvHeaderError()),
            action = stringResource(R.string.tv_files_retry),
            onAction = { onEvent(FilesBrowserEvent.Retry) },
            error = true,
        )
    }
}

private fun FilesFolderOperation.reloadsListing(): Boolean =
    this is FilesFolderOperation.Loading &&
        (intent == FilesFolderOperationIntent.Refresh || intent is FilesFolderOperationIntent.Sort)

@StringRes
private fun FilesFolderOperation.Failed.tvHeaderError(): Int =
    when {
        intent == FilesFolderOperationIntent.Refresh -> R.string.tv_files_refresh_error
        phase == FilesFolderOperationPhase.PERSISTING_SORT -> R.string.tv_files_sort_error
        else -> R.string.tv_files_reload_error
    }

@Composable
internal fun TvFilesPaging(
    paging: FilesPaging,
    enabled: Boolean,
    onEvent: (FilesBrowserEvent) -> Boolean,
    modifier: Modifier = Modifier,
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
        modifier = modifier
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

private val FilesDeleteOutcome.isConfirmedTrash: Boolean
    get() = intent.mode == FilesDeleteMode.TRASH && failure == null &&
        status == FilesDeleteStatus.NO_LONGER_AVAILABLE

@Composable
private fun TvFilesTrashedAnnouncement(
    current: FilesFolderState,
    snackbar: TvSnackbarState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onUndoTrash: ((FilesDeleteOutcome) -> Unit)?,
) {
    val trashed = current.deleteOutcome?.takeIf {
        current.operation == FilesFolderOperation.Idle && it.isConfirmedTrash && !it.announced
    }
    val text = stringResource(R.string.tv_files_trashed)
    val undo = stringResource(R.string.tv_files_undo)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentOnUndo by rememberUpdatedState(onUndoTrash)
    LaunchedEffect(trashed?.requestId) {
        val outcome = trashed ?: return@LaunchedEffect
        snackbar.show(
            TvSnackbarMessage(
                text = text,
                actionLabel = undo.takeIf { currentOnUndo != null },
                onAction = { currentOnUndo?.invoke(outcome) },
            ),
        )
        currentOnEvent(FilesBrowserEvent.DeleteOutcomeAnnounced(outcome))
    }
}

private val SNACKBAR_BOTTOM = 3.dp

private val FOLDER_HEADER_HEIGHT = 76.dp

private val ROOT_TOP_GAP = 4.dp
private val FOLDER_STATE_TOP = 46.dp
private val STATE_BOTTOM_GAP = 3.dp
private val SECTION_LABEL = PutioTypeScale.tvLabel
private val SECTION_LABEL_MONO = PutioTypeScale.tvMono.copy(fontWeight = FontWeight.Medium)
