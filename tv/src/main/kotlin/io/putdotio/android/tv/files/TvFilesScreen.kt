package io.putdotio.android.tv.files

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.tv.ROW_BLEED
import io.putdotio.android.tv.TvListDetail
import io.putdotio.android.tv.TvPlayStart
import io.putdotio.android.tv.TV_MONO_RUN
import io.putdotio.android.tv.TvSectionLine
import io.putdotio.android.tv.TvSectionPill
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.rememberTvPaneHasFocus
import io.putdotio.android.tv.TvDialog
import io.putdotio.android.tv.TvEmptyState
import io.putdotio.android.tv.TvEmptyStateAction
import io.putdotio.android.tv.TvNoticeDialog
import io.putdotio.android.tv.returnDestination
import io.putdotio.android.tv.TvStatusScreen
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
    /** An explanation from outside the browser (a failed watched write, no VLC); OK dismisses it. */
    notice: String? = null,
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
    val headerOwnsFocus = content.headerOwnsFocus()
    LaunchedEffect(current.folder.id.value, headerOwnsFocus) {
        sortFocus.requestFocusIfPaneFocused(headerOwnsFocus, paneHasFocus)
    }
    // The shell asks the pane for focus on entry. A Column is not a target itself, so the
    // request is redirected to whichever control this pane currently wants focused.
    val retryFocus = remember { FocusRequester() }
    val pagingFocus = remember { FocusRequester() }
    val headerHasFocus = remember { mutableStateOf(false) }
    val entryTarget = remember { mutableStateOf(FocusRequester.Default) }
    val listEntry = remember { mutableStateOf(FocusRequester.Default) }
    entryTarget.value = content.paneEntryTarget(sortFocus, retryFocus, pagingFocus, listEntry)
    val folderId = current.folder.id.value
    var detailId by remember(sessionKey, folderId) { mutableStateOf(focusMemory[folderId]) }
    val detailItem = content.detailItem(detailId, focusMemory[folderId])
    val detailFocus = remember { TvFilesDetailFocus() }
    val listing = rememberTvFilesListing(current)
    val actionsOf = { item: FilesItem ->
        item.tvActions(watchedToggleEnabled, confirmedTrashEnabled, current.operation.canStartOperation)
    }
    val dialogReturn = remember { mutableStateOf(FocusRequester.Default) }
    dialogReturn.value = detailFocus.dialogReturn(detailItem.offersActions(actionsOf), entryTarget.value)
    val dialogWasOpen = remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) { refocusAfterDialog(dialogOpen, dialogWasOpen, paneHasFocus, dialogReturn) }
    // As in tv-native, a row with nothing to offer opens no menu.
    val openActions = { item: FilesItem -> if (actionsOf(item).isNotEmpty()) actionsFor = item.id.value }
    val open = { item: FilesItem -> item.open(onEvent, onPlayMedia, onUnsupported = { unsupported = it.id.value }) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .focusProperties { enter = { entryTarget.value } }
            .then(trackPaneFocus)
            .focusGroup(),
    ) {
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
                    onFocusChanged = {
                        headerHasFocus.value = it
                        detailFocus.onListFocusChanged(it)
                    },
                )
                TvFilesOperationStatus(current, onEvent)
                TvFilesDeleteStatus(current, onEvent)
                TvFilesContent(
                    current = current,
                    focusMemory = focusMemory,
                    paneHasFocus = paneHasFocus,
                    headerHasFocus = headerHasFocus,
                    detailOwnsFocus = detailFocus.owns,
                    retryFocus = retryFocus,
                    pagingFocus = pagingFocus,
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
                detailItem?.let { item ->
                    TvFilesDetail(
                        item = item,
                        detailHasFocus = detailFocus.hasFocus,
                        primaryFocus = detailFocus.primary,
                        resumePlayback = resumePlayback,
                        sources = detailSources,
                        listing = listing,
                        actions = TvFilesDetailActions(
                            onOpenFolder = open,
                            onPlay = onPlay,
                            onUnsupported = { unsupported = it.id.value },
                            onMore = openActions.takeIf { actionsOf(item).isNotEmpty() },
                        ),
                        moreFocus = detailFocus.more,
                    )
                }
            },
        )
    }

    if (notice != null) {
        TvNoticeDialog(title = notice, onDismiss = onDismissNotice)
    } else if (actionsItem != null) {
        TvFilesActionsMenu(
            item = actionsItem,
            folder = current,
            trashEnabled = confirmedTrashEnabled,
            watchedToggleEnabled = watchedToggleEnabled,
            confirmingPermanentDelete = confirmingPermanentDelete,
            onConfirmPermanentDelete = { confirmingPermanentDelete = true },
            trashPressed = trashPressed,
            onTrashPressedChange = { trashPressed = it },
            onEvent = onEvent,
            onOpenInVlc = onOpenInVlc,
            onSetWatched = onSetWatched,
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

private fun FilesContent.headerOwnsFocus(): Boolean =
    this is FilesContent.Loading || (this is FilesContent.Empty && paging is FilesPaging.Complete)

/** For a listing, the target is the row the list last reported in [listEntry]. */
private fun FilesContent.paneEntryTarget(
    sort: FocusRequester,
    retry: FocusRequester,
    paging: FocusRequester,
    listEntry: State<FocusRequester>,
): FocusRequester =
    when {
        headerOwnsFocus() -> sort
        this is FilesContent.Failed -> retry
        this is FilesContent.Empty -> paging
        this is FilesContent.Ready -> listEntry.value
        else -> FocusRequester.Default
    }

private fun FocusRequester.requestFocusIfPaneFocused(owns: Boolean, paneHasFocus: State<Boolean>) {
    if (owns && paneHasFocus.value) requestFocus()
}

@Composable
private fun TvFilesContent(
    current: FilesFolderState,
    focusMemory: MutableMap<Long, Long>,
    paneHasFocus: State<Boolean>,
    headerHasFocus: State<Boolean>,
    detailOwnsFocus: State<Boolean>,
    retryFocus: FocusRequester,
    pagingFocus: FocusRequester,
    onEntryTarget: (FocusRequester) -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onOpen: (FilesItem) -> Unit,
    onActions: (FilesItem) -> Unit,
    onRowFocused: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val content = current.content) {
        is FilesContent.Loading ->
            TvStatusScreen(stringResource(R.string.tv_files_loading), modifier = modifier)
        is FilesContent.Failed ->
            TvEmptyState(
                glyph = PutioEmptyGlyph.CouldNotLoad,
                headline = stringResource(R.string.tv_files_error_title),
                message = content.failure.tvMessageText(),
                action = TvEmptyStateAction(
                    label = stringResource(R.string.tv_files_retry),
                    onClick = { onEvent(FilesBrowserEvent.Retry) },
                    icon = R.drawable.ic_ph_arrow_clockwise,
                ),
                modifier = modifier,
                actionFocus = retryFocus,
                claimFocus = paneHasFocus.value,
            )
        is FilesContent.Empty ->
            Column(modifier = modifier) {
                TvStatusScreen(stringResource(R.string.tv_files_empty), modifier = Modifier.weight(1f))
                LaunchedEffect(content.paging is FilesPaging.Complete) {
                    pagingFocus.requestFocusIfPaneFocused(content.paging !is FilesPaging.Complete, paneHasFocus)
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
                detailOwnsFocus = detailOwnsFocus,
                onEntryTarget = onEntryTarget,
                modifier = modifier,
                onEvent = onEvent,
                onOpen = onOpen,
                onActions = onActions,
                onRowFocused = onRowFocused,
            )
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
        operation == FilesFolderOperation.Idle -> current.deleteOutcome?.let { outcome ->
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
        TvDialog(
            title = stringResource(R.string.tv_files_trash_limit_title),
            message = stringResource(R.string.tv_files_trash_limit_message),
            onDismiss = close,
            body = {
                // Bounded like TvDialog's message so a long name cannot push the actions off screen.
                Text(
                    listed.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        ) { focus ->
            TvButton(
                onClick = {
                    close()
                    onEvent(delete)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.tv_files_trash_limit_confirm))
            }
            TvButton(onClick = close, modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                Text(stringResource(R.string.tv_files_cancel))
            }
        }
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
        pill = {
            TvSectionPill(
                icon = R.drawable.ic_ph_sort_ascending,
                label = stringResource(current.folder.sort?.tvLabel() ?: R.string.tv_files_sort_account_default),
                onClick = { if (idle) sorting = true },
                modifier = Modifier.focusRequester(sortFocus),
                showFocus = !sorting,
            )
        },
    ) {
        val summary = current.summary
        if (current.folder.name == null) {
            Text(stringResource(R.string.tv_files_all_files), style = SECTION_LABEL)
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
        TvFilesNotice(text = stringResource(R.string.tv_files_loading))
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

private fun monoNumber(label: String, number: String): AnnotatedString =
    buildAnnotatedString {
        append(label)
        val start = label.indexOf(number)
        if (start >= 0) {
            addStyle(TV_MONO_RUN, start, start + number.length)
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

private val FOLDER_HEADER_HEIGHT = 76.dp

private val ROOT_TOP_GAP = 4.dp
private val SECTION_LABEL = PutioTypeScale.tvLabel
private val SECTION_LABEL_MONO = PutioTypeScale.tvMono.copy(fontWeight = FontWeight.Medium)
