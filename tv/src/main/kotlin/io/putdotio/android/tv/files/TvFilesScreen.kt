package io.putdotio.android.tv.files

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
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
import io.putdotio.android.files.FilesMoveOutcome
import io.putdotio.android.files.FilesMoveStatus
import io.putdotio.android.files.canStartMove
import io.putdotio.android.PutioFailure
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesSort
import io.putdotio.android.tv.TV_MONO_RUN
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.tv.LocalTvOffline
import io.putdotio.android.tv.ROW_BLEED
import io.putdotio.android.tv.TvListDetail
import io.putdotio.android.tv.TvPlayStart
import io.putdotio.android.tv.TvFocusHandBack
import io.putdotio.android.tv.handsBackFocus
import io.putdotio.android.tv.monoNumber
import io.putdotio.android.tv.TvSectionLine
import io.putdotio.android.tv.TvSectionPill
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.rememberTvPaneHasFocus
import io.putdotio.android.tv.TvEmptyState
import io.putdotio.android.tv.TvEmptyStateAction
import io.putdotio.android.tv.TvListSkeleton
import io.putdotio.android.tv.TvAutoPaging
import io.putdotio.android.tv.TvListPaging
import io.putdotio.android.tv.TvDetailSkeleton
import io.putdotio.android.tv.TvLoadFailedState
import io.putdotio.android.tv.TV_LIST_WIDTH
import androidx.compose.foundation.layout.offset
import io.putdotio.android.tv.TvNoticeDialog
import io.putdotio.android.tv.TvConfirmDialog
import io.putdotio.android.tv.TvButtonSize
import io.putdotio.android.tv.TvButtonStyle
import io.putdotio.android.tv.TvTrashUndoOffered
import io.putdotio.android.tv.TvUndoSnackbarHost
import io.putdotio.android.tv.TvSnackbarMessage
import io.putdotio.android.tv.TvSnackbarState
import io.putdotio.android.tv.TvTrashUndoOffer
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.settings.StreamingMethod
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
    trashUndo: TvTrashUndoOffer? = null,
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
    playbackMethod: StreamingMethod? = null,
    onPlayWith: (FilesItem, StreamingMethod) -> Unit = { _, _ -> },
    resumePlayback: Boolean = true,
    detailSources: TvFilesDetailSources = remember(sessionKey) { TvFilesDetailSources.None },
    /** Opens images, PDFs and text in the viewer; without it they land on the unsupported screen. */
    viewerUrl: TvViewerUrl? = null,
    accountDefaultSort: FilesSort? = null,
    edits: TvFilesEdits? = null,
    onAuthenticationRequired: suspend () -> Unit = {},
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

    val actions = rememberSaveable(sessionKey, current.folder.id.value, saver = TvFilesActionsTarget.Saver) {
        TvFilesActionsTarget()
    }
    val actionsItem = (current.content as? FilesContent.Ready)?.items?.firstOrNull { it.id.value == actions.itemId }
    val confirmationStale =
        actions.confirmingPermanentDelete && !current.permanentDeleteConfirmable(confirmedTrashEnabled)
    val edit = rememberSaveable(sessionKey, current.folder.id.value, saver = TvFilesEditTarget.Saver) {
        TvFilesEditTarget()
    }
    val listed = (current.content as? FilesContent.Ready)?.items.orEmpty()
    val renamingItem = listed.firstOrNull { it.id.value == edit.renaming }
    val movingItem = listed.firstOrNull { it.id.value == edit.moving }
    SideEffect { edit.settle(renamingItem, movingItem) }
    SideEffect { actions.settle(orphaned = actions.itemId != null && actionsItem == null, confirmationStale) }
    val dialogOpen = listOfNotNull(actionsItem, notice, renamingItem, movingItem).isNotEmpty()
    val centredDialogOpen =
        centredDialogOpen(notice, actionsItem, actions.confirmingPermanentDelete, confirmationStale) ||
            renamingItem != null
    val snackbar = remember(sessionKey) { TvSnackbarState() }
    val undoFocus = remember { FocusRequester() }
    val downToUndo = undoFocus.takeIf { snackbar.showing?.message?.actionLabel != null }
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
        playbackMethod = playbackMethod,
        canRename = edits != null,
        canMove = edits != null,
    )
    val actionContextOf = { item: FilesItem -> actionContext.copy(pinned = item.pinnedIn(pinnedIds)) }
    val actionsOf = { item: FilesItem -> item.tvActions(actionContextOf(item)) }
    val detailActionsOf = { item: FilesItem ->
        item.tvActions(actionContextOf(item).copy(canDelete = true)).secondary()
    }
    val handlers = TvFilesActionHandlers(
        onPlay = onPlay,
        onPlayWith = onPlayWith,
        onOpenInVlc = onOpenInVlc,
        onSetWatched = onSetWatched,
        onSetPinned = onSetPinned,
        onEdit = edit::start,
    )
    val dialogReturn = remember { mutableStateOf(FocusRequester.Default) }
    dialogReturn.value = detailFocus.dialogReturn(detailItem, detailActionsOf, entryTarget.value)
    val dialogWasOpen = remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) { refocusAfterDialog(dialogOpen, dialogWasOpen, paneHasFocus, dialogReturn) }
    val noticeFocus = remember { TvFocusHandBack() }
    val dialogShowing = rememberUpdatedState(dialogOpen)
    noticeFocus.Refocus(paneHasFocus, blocked = dialogShowing, target = entryTarget)
    // As in tv-native, a row with nothing to offer opens no menu.
    val openActions = { item: FilesItem -> if (actionsOf(item).isNotEmpty()) actions.openSheet(item) }
    val runDetailAction = { item: FilesItem, action: TvFilesAction ->
        if (action is TvFilesAction.Delete) actions.pressDelete(item, action) else handlers.run(action, item)
    }
    TvFilesDetailLeaves(detailItem, folderId, detailFocus, paneHasFocus, entryTarget)
    val viewer = remember(sessionKey, folderId) { TvFileViewerState() }
    val openOther = { item: FilesItem, returnFocus: () -> FocusRequester ->
        viewer.openOr(item, viewerUrl, returnFocus) { unsupported = it.id.value }
    }
    val open = { item: FilesItem ->
        item.open(onEvent, onPlayMedia, onUnsupported = { openOther(it) { entryTarget.value } })
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
                    noticeFocus = noticeFocus,
                    retryFocus = retryFocus,
                    pagingFocus = pagingFocus,
                    emptyFocus = emptyFocus,
                )
            } else {
                TvListDetail(
                    modifier = Modifier.weight(1f),
                    header = { TvFilesFolderHeader(state) },
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
                            accountDefaultSort = accountDefaultSort,
                            onFocusChanged = {
                                headerHasFocus.value = it
                                detailFocus.onListFocusChanged(it)
                            },
                        )
                        TvFilesOperationStatus(current, onEvent, noticeFocus)
                        TvFilesDeleteStatus(current, onEvent, noticeFocus)
                        TvFilesMoveStatus(current, onEvent, noticeFocus)
                        TvFilesRows(
                            current = current,
                            focusMemory = focusMemory,
                            paneHasFocus = paneHasFocus,
                            headerHasFocus = headerHasFocus,
                            detailOwnsFocus = detailFocus.owns,
                            skeletonFocus = skeletonFocus,
                            downToUndo = downToUndo,
                            onEntryTarget = { listEntry.value = it },
                            rowsShowFocus = !centredDialogOpen,
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
                            onUnsupported = { openOther(it) { detailFocus.primary } },
                            secondaryOf = detailActionsOf,
                            onSecondary = runDetailAction,
                            enabled = TvFilesDetailEnabled(current.operation.canStartOperation, state.canStartMove),
                        )
                    },
                )
            }
        }
        TvFilesTrashSnackbar(current, snackbar, onEvent, trashUndo, undoFocus, refocus = entryTarget)
        TvFilesMoveAnnouncement(current, snackbar, edits)
    }

    TvFileViewerLayer(viewer, (content as? FilesContent.Ready)?.items.orEmpty(), viewerUrl, siblings = true)
    TvFilesOverlays(
        notice = notice,
        noticeMessage = noticeMessage,
        onDismissNotice = onDismissNotice,
        renamingItem = renamingItem,
        movingItem = movingItem,
        edits = edits,
        canStartMove = state.canStartMove,
        current = current,
        edit = edit,
        onEvent = onEvent,
        onAuthenticationRequired = onAuthenticationRequired,
        focusMemory = focusMemory,
        listed = listed,
        actionsItem = actionsItem,
        actionContextOf = actionContextOf,
        actions = actions,
        handlers = handlers,
    )
}

@Composable
private fun TvFilesOverlays(
    notice: String?,
    noticeMessage: String?,
    onDismissNotice: () -> Unit,
    renamingItem: FilesItem?,
    movingItem: FilesItem?,
    edits: TvFilesEdits?,
    canStartMove: Boolean,
    current: FilesFolderState,
    edit: TvFilesEditTarget,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onAuthenticationRequired: suspend () -> Unit,
    focusMemory: MutableMap<Long, Long>,
    listed: List<FilesItem>,
    actionsItem: FilesItem?,
    actionContextOf: (FilesItem) -> TvFilesActionContext,
    actions: TvFilesActionsTarget,
    handlers: TvFilesActionHandlers,
) {
    val folderId = current.folder.id.value
    if (notice != null) {
        TvNoticeDialog(
            title = notice,
            message = noticeMessage,
            onDismiss = onDismissNotice,
            okStyle = TvButtonStyle.Filled,
            balanceMessage = true,
            okHugsLabel = true,
            dimAmount = FILES_SHEET_SCRIM.alpha,
        )
    } else if (renamingItem != null) {
        TvFilesListingRename(
            item = renamingItem,
            folder = current,
            onEvent = onEvent,
            onClose = { edit.renaming = null },
        )
    } else if (movingItem != null && edits != null) {
        TvFilesMovePicker(
            item = movingItem,
            sourceFolderId = current.folder.id,
            edits = edits,
            canSubmit = canStartMove,
            onAuthenticationRequired = onAuthenticationRequired,
            onConfirm = { destination ->
                val sent = onEvent(FilesBrowserEvent.Move(current.folder.id, listOf(movingItem.id), destination))
                if (sent) focusMemory[folderId] = listed.neighbourOf(movingItem).id.value
                sent
            },
            onDismiss = { edit.moving = null },
        )
    } else if (actionsItem != null) {
        TvFilesActionsMenu(
            item = actionsItem,
            folder = current,
            context = actionContextOf(actionsItem),
            showSheet = actions.sheet,
            confirmingPermanentDelete = actions.confirmingPermanentDelete,
            onConfirmPermanentDelete = { actions.confirmingPermanentDelete = true },
            trashPressed = actions.trashPressed,
            onTrashPressedChange = { actions.trashPressed = it },
            onEvent = { event ->
                onEvent(event).also { sent ->
                    if (sent && event is FilesBrowserEvent.Delete) {
                        focusMemory[folderId] = listed.neighbourOf(actionsItem).id.value
                    }
                }
            },
            handlers = handlers,
            onClose = actions::close,
        )
    }
}

@Composable
private fun TvFilesFolderHeader(state: FilesBrowserState) {
    TvFilesNamedFolderHeader(state)
    if (state.current.folder.name == null) Spacer(Modifier.height(ROOT_TOP_GAP))
}

@Composable
private fun TvFilesNamedFolderHeader(state: FilesBrowserState) {
    val folderName = state.current.folder.name ?: return
    Box(modifier = Modifier.height(FOLDER_HEADER_HEIGHT)) {
        TvFolderHeading(folderName, crumb = state.crumbLabel())
    }
}

@Composable
internal fun TvFolderHeading(name: String, crumb: String) {
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
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 27.sp, lineHeight = 31.sp),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.MiddleEllipsis,
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
        is FilesContent.Empty -> if (paging is FilesPaging.Failed) pagingControl else emptyState
        is FilesContent.Ready -> listEntry.value
    }

private fun centredDialogOpen(
    notice: String?,
    actionsItem: FilesItem?,
    confirmingDelete: Boolean,
    confirmationStale: Boolean,
): Boolean = notice != null || (actionsItem != null && confirmingDelete && !confirmationStale)

private fun FilesContent.fillsPane(): Boolean = this is FilesContent.Failed || this is FilesContent.Empty

@Composable
private fun TvFilesRows(
    current: FilesFolderState,
    focusMemory: MutableMap<Long, Long>,
    paneHasFocus: State<Boolean>,
    headerHasFocus: State<Boolean>,
    detailOwnsFocus: State<Boolean>,
    skeletonFocus: FocusRequester,
    downToUndo: FocusRequester?,
    onEntryTarget: (FocusRequester) -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onOpen: (FilesItem) -> Unit,
    onActions: (FilesItem) -> Unit,
    onRowFocused: (FilesItem) -> Unit,
    rowsShowFocus: Boolean,
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
            downFromLast = downToUndo,
            onEntryTarget = onEntryTarget,
            modifier = modifier,
            onEvent = onEvent,
            onOpen = onOpen,
            onActions = onActions,
            onRowFocused = onRowFocused,
            rowsShowFocus = rowsShowFocus,
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
    secondaryOf: (FilesItem) -> List<TvFilesAction>,
    onSecondary: (FilesItem, TvFilesAction) -> Unit,
    enabled: TvFilesDetailEnabled,
) {
    if (loading) TvDetailSkeleton()
    if (item == null) return
    TvFilesDetail(
        item = item,
        focus = detailFocus,
        resumePlayback = resumePlayback,
        sources = sources,
        listing = listing,
        actions = TvFilesDetailActions(
            onOpenFolder = onOpenFolder,
            onPlay = onPlay,
            onUnsupported = onUnsupported,
            secondary = secondaryOf(item),
            onSecondary = onSecondary,
            enabled = enabled,
        ),
    )
}

@Composable
private fun TvFilesDetailLeaves(
    detailItem: FilesItem?,
    folderId: Long,
    detailFocus: TvFilesDetailFocus,
    paneHasFocus: State<Boolean>,
    entryTarget: State<FocusRequester>,
) {
    val shown = detailItem?.id?.value
    val previous = remember(folderId) { mutableStateOf(shown) }
    LaunchedEffect(folderId, shown) {
        val left = previous.value != null && shown != null && previous.value != shown
        previous.value = shown
        if (!left || !detailFocus.owns.value || !paneHasFocus.value) return@LaunchedEffect
        withFrameNanos {}
        if (paneHasFocus.value) entryTarget.value.requestFocus()
    }
}

@Composable
private fun TvFilesStatePane(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    paneHasFocus: State<Boolean>,
    noticeFocus: TvFocusHandBack,
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
                TvFilesOperationStatus(current, onEvent, noticeFocus)
                TvFilesDeleteStatus(current, onEvent, noticeFocus)
                TvFilesMoveStatus(current, onEvent, noticeFocus)
            }
        }
        when (val content = current.content) {
            is FilesContent.Failed -> TvLoadFailedState(
                failure = content.failure,
                headline = stringResource(R.string.tv_files_error_title),
                onRetry = { onEvent(FilesBrowserEvent.Retry) },
                modifier = stateModifier,
                actionFocus = retryFocus,
                claimFocus = paneHasFocus.value,
            )
            is FilesContent.Empty -> Column(modifier = stateModifier) {
                val failed = content.paging is FilesPaging.Failed
                val enabled = current.operation.canStartOperation
                TvEmptyState(
                    glyph = PutioEmptyGlyph.EmptyFolder,
                    headline = stringResource(R.string.tv_files_empty_title),
                    message = stringResource(R.string.tv_files_empty_message),
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (!failed) {
                                Modifier
                                    .focusRequester(emptyFocus)
                                    .semantics(mergeDescendants = true) {}
                                    .focusable()
                            } else {
                                Modifier
                            },
                        ),
                )
                LaunchedEffect(failed) {
                    if (paneHasFocus.value) (if (failed) pagingFocus else emptyFocus).requestFocus()
                }
                TvAutoPaging(
                    page = (content.paging as? FilesPaging.Available)?.takeIf { enabled },
                    onLoadMore = { onEvent(FilesBrowserEvent.LoadNextPage) },
                )
                TvFilesPaging(
                    paging = content.paging,
                    enabled = enabled,
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
    noticeFocus: TvFocusHandBack,
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
                handBack = noticeFocus,
            )
        }
        operation == FilesFolderOperation.Idle &&
            current.deleteOutcome?.status == FilesDeleteStatus.TOO_LARGE_FOR_TRASH ->
            TvFilesTrashLimitStatus(checkNotNull(current.deleteOutcome), current, onEvent, noticeFocus)
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
internal fun FilesDeleteStatus.tvOutcomeMessage(): Int? =
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
    noticeFocus: TvFocusHandBack,
) {
    var confirming by rememberSaveable(outcome.requestId.value) { mutableStateOf(false) }
    // A refresh that no longer lists the folder leaves nothing for the reducer to delete; a
    // rename keeps the outcome, so the dialog names the folder as it is listed now.
    val listed = current.content.items().firstOrNull { it.id in outcome.intent.itemIds }
    TvFilesNotice(
        text = stringResource(R.string.tv_files_trash_limit_title),
        action = stringResource(R.string.tv_files_action_delete).takeIf { listed != null },
        onAction = { confirming = true },
        handBack = noticeFocus,
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
            dimAmount = FILES_SHEET_SCRIM.alpha,
        )
    }
}

/** A line above the rows, with an optional action in its own focus group so Up from a row reaches it. */
@Composable
internal fun TvFilesNotice(
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
    error: Boolean = false,
    handBack: TvFocusHandBack? = null,
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
        if (action != null) {
            TvButton(
                onClick = onAction,
                modifier = if (handBack != null) Modifier.handsBackFocus(handBack) else Modifier,
            ) { Text(action) }
        }
    }
}

@Composable
private fun TvFilesSectionLine(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    sortFocus: FocusRequester,
    sessionKey: Any?,
    showSort: Boolean,
    accountDefaultSort: FilesSort?,
    onFocusChanged: (Boolean) -> Unit,
) {
    val current = state.current
    val sortInEffect = current.folder.sort ?: accountDefaultSort
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
                    label = stringResource(sortInEffect?.tvLabel() ?: R.string.tv_files_sort_title),
                    onClick = { if (idle) sorting = true },
                    modifier = Modifier.focusRequester(sortFocus),
                    showFocus = !sorting,
                )
                TvSectionPill(
                    icon = R.drawable.ic_ph_arrows_clockwise,
                    label = stringResource(R.string.tv_files_refresh),
                    onClick = { if (idle) onEvent(FilesBrowserEvent.Refresh) },
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
                stringResource(R.string.tv_files_title),
                style = SECTION_LABEL,
                modifier = if (showSort) Modifier else Modifier.align(Alignment.Top),
            )
        } else if (summary != null) {
            val count = summary.itemCount.toString()
            val label = pluralStringResource(R.plurals.tv_files_item_count, summary.itemCount, count)
            val size = summary.sizeBytes?.let { formatFileSize(it) }
            Text(
                buildAnnotatedString {
                    append(monoNumber(label, count))
                    if (size != null) {
                        append(COUNT_SEPARATOR)
                        withStyle(SECTION_SIZE_RUN) { append(size) }
                    }
                },
                style = SECTION_LABEL,
            )
        }
    }
    if (sorting) {
        TvSortSheet(
            title = stringResource(R.string.tv_files_sort_title),
            selected = sortInEffect,
            onSelect = { sort ->
                sorting = false
                onEvent(FilesBrowserEvent.SelectSort(sort))
            },
            onDismiss = { sorting = false },
            returnFocus = sortFocus,
            scrim = FILES_SHEET_SCRIM,
        )
    }
}

@Composable
private fun TvFilesOperationStatus(
    current: FilesFolderState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    noticeFocus: TvFocusHandBack,
) {
    if (current.operation.reloadsListing()) {
        TvFilesNotice(text = stringResource(R.string.tv_state_loading))
    }
    val offline = LocalTvOffline.current
    val failed = (current.operation as? FilesFolderOperation.Failed)
        ?.takeUnless { it.intent is FilesFolderOperationIntent.Delete || it.intent is FilesFolderOperationIntent.Move }
        ?.takeUnless {
            it.intent is FilesFolderOperationIntent.Rename && it.phase == FilesFolderOperationPhase.RENAMING
        }
        ?.takeUnless {
            offline && it.intent == FilesFolderOperationIntent.Refresh && it.failure is PutioFailure.NetworkUnavailable
        }
    if (failed != null) {
        TvFilesNotice(
            text = stringResource(failed.tvHeaderError()),
            action = stringResource(R.string.tv_files_retry),
            onAction = { onEvent(FilesBrowserEvent.Retry) },
            error = true,
            handBack = noticeFocus,
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
        intent is FilesFolderOperationIntent.Rename -> R.string.tv_files_rename_reload_error
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
    if (paging == FilesPaging.Complete) return
    TvListPaging(
        failed = paging is FilesPaging.Failed,
        loadingMore = stringResource(R.string.tv_files_loading_more),
        error = stringResource(R.string.tv_files_paging_error),
        retry = stringResource(R.string.tv_files_retry),
        onRetry = { if (enabled) onEvent(FilesBrowserEvent.Retry) },
        modifier = modifier,
        buttonModifier = buttonModifier,
    )
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
            softWrap = false,
            overflow = TextOverflow.MiddleEllipsis,
        )
        TvEmptyState(
            glyph = PutioEmptyGlyph.NoResults,
            headline = stringResource(R.string.tv_files_unsupported_title),
            message = stringResource(R.string.tv_files_unsupported_message),
            action = TvEmptyStateAction(stringResource(R.string.tv_files_go_back), onBack),
            claimFocus = claimFocus,
            modifier = Modifier.weight(1f),
        )
    }
}

internal val FilesDeleteOutcome.isConfirmedTrash: Boolean
    get() = intent.mode == FilesDeleteMode.TRASH && failure == null &&
        status == FilesDeleteStatus.NO_LONGER_AVAILABLE

@Composable
private fun TvFilesTrashedAnnouncement(
    current: FilesFolderState,
    snackbar: TvSnackbarState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    trashUndo: TvTrashUndoOffer?,
) {
    val trashed = current.deleteOutcome?.takeIf {
        current.operation == FilesFolderOperation.Idle && it.isConfirmedTrash && !it.announced
    }
    val text = stringResource(R.string.tv_files_trashed)
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(trashed?.requestId) {
        val outcome = trashed ?: return@LaunchedEffect
        if (trashUndo != null) trashUndo.offer(outcome) else snackbar.show(TvSnackbarMessage(text = text))
        currentOnEvent(FilesBrowserEvent.DeleteOutcomeAnnounced(outcome))
    }
    TvTrashUndoOffered(trashUndo, snackbar)
}

@Composable
private fun BoxScope.TvFilesTrashSnackbar(
    current: FilesFolderState,
    snackbar: TvSnackbarState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    trashUndo: TvTrashUndoOffer?,
    undoFocus: FocusRequester,
    refocus: State<FocusRequester>,
) {
    TvFilesTrashedAnnouncement(current, snackbar, onEvent, trashUndo)
    TvUndoSnackbarHost(snackbar, undoFocus, refocus)
}

/**
 * The row whose actions are up, whether they show as a sheet (the list's shortcut) or only run a
 * deletion pressed in the detail pane, and whether that deletion is being confirmed. All close
 * when the row leaves the listing, like the unsupported overlay.
 */
@Stable
private class TvFilesActionsTarget(itemId: Long? = null, sheet: Boolean = false, confirming: Boolean = false) {
    var itemId by mutableStateOf(itemId)
        private set
    var sheet by mutableStateOf(sheet)
        private set

    // Only permanent deletion confirms; Move to trash runs at once, as on web and iOS. Trash
    // turning on underneath, or an operation the folder starts on its own (a reload after a
    // restore), closes the confirmation rather than offering a Delete the reducer would refuse.
    var confirmingPermanentDelete by mutableStateOf(confirming)

    // A Move to trash press is sent after the next composition, against the latest Trash setting:
    // a press made before a change to the setting reached the menu must not send TRASH, which
    // the server applies as permanent deletion once Trash is off.
    var trashPressed by mutableStateOf(false)

    fun openSheet(item: FilesItem) {
        itemId = item.id.value
        sheet = true
    }

    fun pressDelete(item: FilesItem, delete: TvFilesAction.Delete) {
        itemId = item.id.value
        sheet = false
        if (delete.trash) trashPressed = true else confirmingPermanentDelete = true
    }

    fun close() {
        itemId = null
        sheet = false
        confirmingPermanentDelete = false
        trashPressed = false
    }

    fun settle(orphaned: Boolean, confirmationStale: Boolean) {
        when {
            orphaned -> close()
            confirmationStale -> confirmingPermanentDelete = false
            itemId != null && !sheet && !pending -> itemId = null
        }
    }

    private val pending: Boolean get() = confirmingPermanentDelete || trashPressed

    companion object {
        val Saver: Saver<TvFilesActionsTarget, Bundle> = Saver(
            save = { target ->
                Bundle().apply {
                    target.itemId?.let { putLong(ITEM_KEY, it) }
                    putBoolean(SHEET_KEY, target.sheet)
                    putBoolean(CONFIRMING_KEY, target.confirmingPermanentDelete)
                }
            },
            restore = { bundle ->
                TvFilesActionsTarget(
                    itemId = bundle.getLong(ITEM_KEY).takeIf { bundle.containsKey(ITEM_KEY) },
                    sheet = bundle.getBoolean(SHEET_KEY),
                    confirming = bundle.getBoolean(CONFIRMING_KEY),
                )
            },
        )

        private const val ITEM_KEY = "item"
        private const val SHEET_KEY = "sheet"
        private const val CONFIRMING_KEY = "confirming"
    }
}

@Stable
private class TvFilesEditTarget(renaming: Long? = null, moving: Long? = null) {
    var renaming by mutableStateOf(renaming)
    var moving by mutableStateOf(moving)

    fun start(action: TvFilesAction, item: FilesItem) {
        when (action) {
            TvFilesAction.Rename -> renaming = item.id.value
            TvFilesAction.Move -> moving = item.id.value
            else -> Unit
        }
    }

    fun settle(renamingItem: FilesItem?, movingItem: FilesItem?) {
        if (renaming != null && renamingItem == null) renaming = null
        if (moving != null && movingItem == null) moving = null
    }

    companion object {
        val Saver: Saver<TvFilesEditTarget, Bundle> = Saver(
            save = { target ->
                Bundle().apply {
                    target.renaming?.let { putLong("renaming", it) }
                    target.moving?.let { putLong("moving", it) }
                }
            },
            restore = { bundle ->
                TvFilesEditTarget(
                    renaming = bundle.getLong("renaming").takeIf { bundle.containsKey("renaming") },
                    moving = bundle.getLong("moving").takeIf { bundle.containsKey("moving") },
                )
            },
        )
    }
}

private fun FilesItem.pinnedIn(pinnedIds: List<FilesItemId>?): Boolean? = pinnedIds?.let { id in it }

private val FOLDER_HEADER_HEIGHT = 76.dp

private val ROOT_TOP_GAP = 4.dp
private val FOLDER_STATE_TOP = 46.dp
private val STATE_BOTTOM_GAP = 3.dp
private val SECTION_LABEL = PutioTypeScale.tvLabel
private val SECTION_SIZE_RUN = TV_MONO_RUN.merge(SpanStyle(fontWeight = FontWeight.Medium))
private const val COUNT_SEPARATOR = " · "
