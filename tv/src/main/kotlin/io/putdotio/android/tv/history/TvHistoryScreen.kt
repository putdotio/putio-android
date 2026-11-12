package io.putdotio.android.tv.history

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.FilesFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.history.HistoryClearing
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryState
import io.putdotio.android.settings.HistoryOpen
import io.putdotio.android.files.FilesItem
import io.putdotio.android.history.navigableFileId
import io.putdotio.android.tv.files.TvFilesAction
import io.putdotio.android.tv.files.TvFilesActionHandlers
import io.putdotio.android.tv.files.TvFilesEdits
import io.putdotio.android.tv.files.TvItemEditLayer
import io.putdotio.android.tv.files.TvItemEditTarget
import io.putdotio.android.tv.files.TvItemThumbnails
import io.putdotio.android.tv.files.run
import io.putdotio.android.tv.TvConfirmDialog
import io.putdotio.android.tv.TvDetailSkeleton
import io.putdotio.android.tv.TvDialog
import io.putdotio.android.tv.TvEmptyState
import io.putdotio.android.tv.TvListDetail
import io.putdotio.android.tv.TvListSkeleton
import io.putdotio.android.tv.TvListPaging
import io.putdotio.android.tv.TvLoadFailedState
import io.putdotio.android.tv.TvNoticeDialog
import io.putdotio.android.tv.TvPickedRow
import io.putdotio.android.tv.LocalTvTabFocus
import io.putdotio.android.tv.TvSectionLine
import io.putdotio.android.tv.TvTrashUndoOffer
import io.putdotio.android.tv.TvTrashUndoOffered
import io.putdotio.android.tv.TvUndoSnackbar
import io.putdotio.android.tv.TvUndoSnackbarHost
import io.putdotio.android.tv.files.rememberBusy
import io.putdotio.android.tv.files.tvMessageText
import io.putdotio.android.tv.refocusAfterDialog
import io.putdotio.android.tv.rememberTvPaneHasFocus
import java.time.Clock
import java.time.LocalDate

internal const val TV_HISTORY_LIST_TAG = "tv-history-list"
internal const val TV_HISTORY_ROW_TAG = "tv-history-row"

private fun HistoryContent.entryTarget(stateFocus: FocusRequester, rowsTarget: FocusRequester): FocusRequester =
    when (this) {
        HistoryContent.Empty -> FocusRequester.Cancel
        is HistoryContent.Ready -> rowsTarget
        else -> stateFocus
    }

private suspend fun placeStateFocus(
    content: HistoryContent,
    paneHasFocus: MutableState<Boolean>,
    stateFocus: FocusRequester,
    tabFocus: FocusRequester?,
) {
    val empty = content == HistoryContent.Empty
    val loading = content is HistoryContent.Loading || content == HistoryContent.Disabled
    if (!(empty || loading) || !paneHasFocus.value) return
    withFrameNanos {}
    if (!paneHasFocus.value) return
    if (empty) {
        paneHasFocus.value = false
        tabFocus?.requestFocus()
    } else {
        stateFocus.requestFocus()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvHistoryScreen(
    state: HistoryState,
    onEvent: (HistoryEvent) -> Boolean,
    modifier: Modifier = Modifier,
    notice: FilesFailure? = null,
    sessionKey: Any? = null,
    clock: Clock = Clock.systemDefaultZone(),
    pickedRow: TvPickedRow = remember(sessionKey) { TvPickedRow() },
    files: TvHistoryFiles = TvHistoryFiles.None,
    onAuthenticationRequired: suspend () -> Unit = {},
    itemActions: TvHistoryItemActions? = null,
    open: HistoryOpen = HistoryOpen.History,
    onRetryOpen: () -> Unit = {},
) = key(sessionKey) {
    LaunchedEffect(Unit) {
        files.forgetAll()
        onEvent(HistoryEvent.Refresh)
    }
    val content = when (open) {
        HistoryOpen.History -> state.content
        is HistoryOpen.Failed -> HistoryContent.Failed(open.failure)
        HistoryOpen.Pending, HistoryOpen.FilesRoot -> HistoryContent.Disabled
    }
    val paneHasFocus = rememberTvPaneHasFocus()
    val listFocus = remember { FocusRequester() }
    val stateFocus = remember { FocusRequester() }
    val clearFocus = remember { FocusRequester() }
    val clearHadFocus = remember { mutableStateOf(pickedRow.id == TV_HISTORY_CLEAR_PICK) }
    val tabFocus = LocalTvTabFocus.current
    val entryTarget = rememberUpdatedState(
        content.entryTarget(stateFocus, if (clearHadFocus.value) clearFocus else listFocus),
    )
    LaunchedEffect(content::class) { placeStateFocus(content, paneHasFocus, stateFocus, tabFocus) }
    val snackbar = remember { TvUndoSnackbar(FocusRequester()) }
    val dialogOpen = state.clearing != HistoryClearing.Idle
    val dialogWasOpen = remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) { refocusAfterDialog(dialogOpen, dialogWasOpen, paneHasFocus, entryTarget) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .onFocusChanged { if (it.hasFocus) paneHasFocus.value = true }
            .focusProperties {
                enter = { entryTarget.value }
                exit = {
                    paneHasFocus.value = false
                    FocusRequester.Default
                }
            }
            .focusGroup(),
    ) {
        val statePane = Modifier
            .fillMaxSize()
            .padding(top = HISTORY_TOP_GAP, bottom = STATE_BOTTOM_GAP)
        when (content) {
            is HistoryContent.Failed ->
                TvLoadFailedState(
                    failure = content.failure,
                    headline = stringResource(R.string.tv_history_error_title),
                    onRetry = { if (open is HistoryOpen.Failed) onRetryOpen() else onEvent(HistoryEvent.Retry) },
                    modifier = statePane,
                    actionFocus = stateFocus,
                    claimFocus = paneHasFocus.value,
                )
            HistoryContent.Empty ->
                TvEmptyState(
                    glyph = PutioEmptyGlyph.NothingYet,
                    headline = stringResource(R.string.tv_history_empty),
                    message = stringResource(R.string.tv_history_empty_message),
                    modifier = statePane,
                )
            HistoryContent.Disabled, is HistoryContent.Loading -> TvHistoryLoading(stateFocus)
            is HistoryContent.Ready ->
                TvHistoryListDetail(
                    content = content,
                    onEvent = onEvent,
                    notice = notice,
                    clock = clock,
                    pickedRow = pickedRow,
                    files = files,
                    focus = TvHistoryFocus(listFocus, clearFocus, clearHadFocus, paneHasFocus),
                    onAuthenticationRequired = onAuthenticationRequired,
                    itemActions = itemActions,
                    snackbar = snackbar,
                )
        }
    }
    TvHistoryClearDialog(state.clearing, onEvent)
}

@Composable
private fun TvHistoryFileGoneFocus(gone: Boolean, detailHadFocus: () -> Boolean, focus: TvHistoryFocus) {
    LaunchedEffect(gone) {
        if (gone && detailHadFocus() && focus.paneHasFocus.value) {
            withFrameNanos {}
            runCatching { focus.list.requestFocus() }
        }
    }
}

@Composable
private fun TvHistoryLoading(skeletonFocus: FocusRequester) {
    Column(Modifier.fillMaxSize()) {
        TvListDetail(
            modifier = Modifier.weight(1f),
            header = { Spacer(Modifier.height(HISTORY_TOP_GAP)) },
            detailFocus = FocusRequester.Cancel,
            list = {
                TvSectionLine {}
                TvListSkeleton(
                    Modifier
                        .focusRequester(skeletonFocus)
                        .focusable(),
                )
            },
            detail = { TvDetailSkeleton() },
        )
    }
}

@Composable
private fun TvHistoryListDetail(
    content: HistoryContent.Ready,
    onEvent: (HistoryEvent) -> Boolean,
    notice: FilesFailure?,
    clock: Clock,
    pickedRow: TvPickedRow,
    files: TvHistoryFiles,
    focus: TvHistoryFocus,
    onAuthenticationRequired: suspend () -> Unit,
    itemActions: TvHistoryItemActions?,
    snackbar: TvUndoSnackbar,
) {
    val restoreRowId = remember { pickedRow.id }
    val today = remember(content.items) { LocalDate.now(clock) }
    val openFocus = remember { FocusRequester() }
    var detailId by remember { mutableStateOf(restoreRowId) }
    val detailItem = content.items.firstOrNull { it.id.value == detailId } ?: content.items.first()
    val detailFileId = detailItem.kind.navigableFileId()
    val lookup = rememberTvHistoryLookup(detailFileId, files, onAuthenticationRequired)
    val detailActionable = lookup is TvHistoryLookup.Found || lookup == TvHistoryLookup.Unknown
    var detailHadFocus by remember { mutableStateOf(false) }
    val edit = remember { TvItemEditTarget() }
    val editing = itemActions?.editing?.edits.rememberBusy()
    TvHistoryFileGoneFocus(lookup == TvHistoryLookup.Gone, { detailHadFocus }, focus)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TvListDetail(
            modifier = Modifier.weight(1f),
            header = { Spacer(Modifier.height(HISTORY_TOP_GAP)) },
            detailFocus = if (detailFileId != null && detailActionable) openFocus else FocusRequester.Cancel,
            listFocus = focus.list,
            onDetailFocusChanged = { if (it) detailHadFocus = true },
            list = {
                if (notice != null) TvHistoryNotice(notice)
                TvHistoryList(
                    content = content,
                    restoreRowId = restoreRowId,
                    today = today,
                    zone = clock.zone,
                    focus = focus,
                    actions = TvHistoryListActions(
                        onOpen = { onEvent(HistoryEvent.OpenFile(it)) },
                        onClear = { onEvent(HistoryEvent.RequestClear) },
                        onRowFocused = {
                            pickedRow.pick(it.id.value)
                            focus.clearHadFocus.value = false
                            detailHadFocus = false
                            detailId = it.id.value
                        },
                        onClearFocused = {
                            pickedRow.pick(TV_HISTORY_CLEAR_PICK)
                            focus.clearHadFocus.value = true
                        },
                        onNextPage = { onEvent(HistoryEvent.LoadNextPage) },
                        onRetry = { onEvent(HistoryEvent.Retry) },
                    ),
                    modifier = Modifier.weight(1f),
                )
            },
            detail = {
                TvHistoryDetail(
                    item = detailItem,
                    lookup = lookup,
                    today = today,
                    zone = clock.zone,
                    openFocus = openFocus,
                    actions = TvHistoryDetailActions(
                        editing = editing,
                        playback = TvHistoryDetailPlayback(
                            resumePlayback = itemActions?.resumePlayback ?: true,
                            thumbnails = itemActions?.thumbnails ?: TvItemThumbnails.None,
                            onPlay = { itemActions?.onPlay?.invoke(it) },
                        ),
                        onGoToFolder = { onEvent(HistoryEvent.GoToFolder(it)) },
                        secondaryOf = { file -> itemActions?.actionsOf?.invoke(file).orEmpty() },
                        onSecondary = { file, action ->
                            val handlers = itemActions?.handlers
                            if (action is TvFilesAction.Delete || action == TvFilesAction.Rename) {
                                edit.press(action, file)
                            } else {
                                handlers?.run(action, file)
                            }
                        },
                    ),
                )
            },
        )
    }
    TvHistorySnackbarHost(snackbar, itemActions?.editing?.trashUndo, focus.list)
    }
    val edits = itemActions?.editing?.edits
    if (edits != null) {
        TvItemEditLayer(
            target = edit,
            edits = edits,
            trashEnabled = itemActions?.editing?.trashEnabled,
            snackbar = snackbar.state,
            onAuthenticationRequired = onAuthenticationRequired,
        )
    }
}

@Composable
private fun BoxScope.TvHistorySnackbarHost(
    snackbar: TvUndoSnackbar,
    trashUndo: TvTrashUndoOffer?,
    list: FocusRequester,
) {
    TvTrashUndoOffered(trashUndo, snackbar.state)
    TvUndoSnackbarHost(snackbar.state, snackbar.undoFocus, rememberUpdatedState(list))
}

internal class TvHistoryItemActions(
    val resumePlayback: Boolean,
    val thumbnails: TvItemThumbnails,
    val onPlay: (FilesItem) -> Unit,
    val actionsOf: (FilesItem) -> List<TvFilesAction>,
    val handlers: TvFilesActionHandlers,
    val editing: TvHistoryEditing? = null,
)

internal class TvHistoryEditing(
    val edits: TvFilesEdits,
    val trashEnabled: Boolean?,
    val trashUndo: TvTrashUndoOffer? = null,
)

@Composable
private fun TvHistoryNotice(notice: FilesFailure) {
    Text(
        text = if (notice == FilesFailure.NavigationBlocked) {
            stringResource(R.string.tv_error_navigation_blocked)
        } else {
            stringResource(R.string.tv_history_open_error, notice.tvMessageText())
        },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = NOTICE_INSET, end = NOTICE_INSET, bottom = 8.dp),
    )
}

@Composable
internal fun TvHistoryPaging(
    paging: HistoryPaging,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
) {
    if (paging == HistoryPaging.Complete) return
    TvListPaging(
        failed = paging is HistoryPaging.Failed,
        loadingMore = stringResource(R.string.tv_history_loading_more),
        error = stringResource(R.string.tv_history_paging_error),
        retry = stringResource(R.string.tv_state_try_again),
        onRetry = onRetry,
        modifier = modifier,
        buttonModifier = buttonModifier,
    )
}

@Composable
private fun TvHistoryClearDialog(clearing: HistoryClearing, onEvent: (HistoryEvent) -> Boolean) {
    when (clearing) {
        HistoryClearing.AwaitingConfirmation ->
            TvConfirmDialog(
                title = stringResource(R.string.tv_history_clear_title),
                message = stringResource(R.string.tv_history_clear_message),
                confirmLabel = stringResource(R.string.tv_history_clear),
                onConfirm = { onEvent(HistoryEvent.ConfirmClear) },
                onDismiss = { onEvent(HistoryEvent.DismissClear) },
                destructive = true,
            )
        is HistoryClearing.Clearing ->
            TvDialog(title = stringResource(R.string.tv_history_clearing), message = null, onDismiss = {})
        is HistoryClearing.Failed ->
            TvNoticeDialog(
                title = stringResource(R.string.tv_history_clear_error_title),
                message = clearing.failure.tvMessageText(),
                onDismiss = { onEvent(HistoryEvent.DismissClear) },
            )
        HistoryClearing.Idle -> Unit
    }
}

internal class TvHistoryListActions(
    val onOpen: (HistoryFileId) -> Unit,
    val onClear: () -> Unit,
    val onRowFocused: (HistoryItem) -> Unit,
    val onClearFocused: () -> Unit,
    val onNextPage: () -> Unit,
    val onRetry: () -> Unit,
)

internal class TvHistoryFocus(
    val list: FocusRequester,
    val clear: FocusRequester,
    val clearHadFocus: MutableState<Boolean>,
    val paneHasFocus: State<Boolean>,
)

internal const val TV_HISTORY_CLEAR_PICK = Long.MIN_VALUE

private val HISTORY_TOP_GAP = 4.dp
private val STATE_BOTTOM_GAP = 3.dp
private val NOTICE_INSET = 12.dp

internal class TvHistoryDetailPlayback(
    val resumePlayback: Boolean,
    val thumbnails: TvItemThumbnails,
    val onPlay: (FilesItem) -> Unit,
)

internal class TvHistoryDetailActions(
    val editing: Boolean,
    val playback: TvHistoryDetailPlayback,
    val onGoToFolder: (HistoryFileId) -> Unit,
    val secondaryOf: (FilesItem) -> List<TvFilesAction>,
    val onSecondary: (FilesItem, TvFilesAction) -> Unit,
)
