package io.putdotio.android.tv.history

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
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
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvConfirmDialog
import io.putdotio.android.tv.TvDetailSkeleton
import io.putdotio.android.tv.TvDialog
import io.putdotio.android.tv.TvEmptyState
import io.putdotio.android.tv.TvEmptyStateAction
import io.putdotio.android.tv.TvListDetail
import io.putdotio.android.tv.TvListSkeleton
import io.putdotio.android.tv.TvLoadFailedState
import io.putdotio.android.tv.TvNoticeDialog
import io.putdotio.android.tv.TvPickedRow
import io.putdotio.android.tv.TvSectionLine
import io.putdotio.android.tv.files.tvLoadFailedMessage
import io.putdotio.android.tv.files.tvMessageText
import io.putdotio.android.tv.refocusAfterDialog
import io.putdotio.android.tv.rememberTvPaneHasFocus
import java.time.Clock
import java.time.LocalDate

internal const val TV_HISTORY_LIST_TAG = "tv-history-list"
internal const val TV_HISTORY_ROW_TAG = "tv-history-row"

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
    onOpenAccount: () -> Unit = {},
    onAuthenticationRequired: suspend () -> Unit = {},
) = key(sessionKey) {
    LaunchedEffect(Unit) { onEvent(HistoryEvent.Refresh) }
    val content = state.content
    val paneHasFocus = rememberTvPaneHasFocus()
    val listFocus = remember { FocusRequester() }
    val stateFocus = remember { FocusRequester() }
    val clearFocus = remember { FocusRequester() }
    val clearHadFocus = remember { mutableStateOf(pickedRow.id == TV_HISTORY_CLEAR_PICK) }
    val entryTarget = rememberUpdatedState(
        when {
            content !is HistoryContent.Ready -> stateFocus
            clearHadFocus.value -> clearFocus
            else -> listFocus
        },
    )
    val holdsOwnFocus = content is HistoryContent.Loading || content == HistoryContent.Empty
    LaunchedEffect(content::class) {
        if (!holdsOwnFocus || !paneHasFocus.value) return@LaunchedEffect
        withFrameNanos {}
        if (paneHasFocus.value) stateFocus.requestFocus()
    }
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
            HistoryContent.Disabled ->
                TvEmptyState(
                    glyph = PutioEmptyGlyph.HistoryOff,
                    headline = stringResource(R.string.tv_history_off_title),
                    message = stringResource(
                        R.string.tv_history_off_message,
                        stringResource(R.string.tv_account_history_enabled),
                        stringResource(R.string.tv_destination_account),
                    ),
                    action = TvEmptyStateAction(
                        label = stringResource(R.string.tv_history_open_account),
                        onClick = onOpenAccount,
                        icon = R.drawable.ic_ph_user_circle,
                    ),
                    modifier = statePane,
                    actionFocus = stateFocus,
                    claimFocus = paneHasFocus.value,
                )
            is HistoryContent.Failed ->
                TvLoadFailedState(
                    failure = content.failure,
                    headline = stringResource(R.string.tv_history_error_title),
                    message = content.failure.tvLoadFailedMessage(),
                    onRetry = { onEvent(HistoryEvent.Retry) },
                    modifier = statePane,
                    actionFocus = stateFocus,
                    claimFocus = paneHasFocus.value,
                )
            HistoryContent.Empty ->
                TvEmptyState(
                    glyph = PutioEmptyGlyph.NothingYet,
                    headline = stringResource(R.string.tv_history_empty),
                    message = null,
                    modifier = statePane
                        .focusRequester(stateFocus)
                        .semantics(mergeDescendants = true) {}
                        .focusable(),
                )
            is HistoryContent.Loading -> TvHistoryLoading(stateFocus)
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
                )
        }
    }
    TvHistoryClearDialog(state.clearing, onEvent)
}

@Composable
private fun TvHistoryLoading(skeletonFocus: FocusRequester) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(HISTORY_TOP_GAP))
        TvListDetail(
            modifier = Modifier.weight(1f),
            detailFocus = FocusRequester.Cancel,
            detailTop = HISTORY_DETAIL_TOP,
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
) {
    val restoreRowId = remember { pickedRow.id }
    val today = remember(content.items) { LocalDate.now(clock) }
    val openFocus = remember { FocusRequester() }
    var detailId by remember { mutableStateOf(restoreRowId) }
    val detailItem = content.items.firstOrNull { it.id.value == detailId } ?: content.items.first()
    val detailFileId = detailItem.kind.navigableFileId()
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(HISTORY_TOP_GAP))
        TvListDetail(
            modifier = Modifier.weight(1f),
            detailFocus = if (detailFileId != null) openFocus else FocusRequester.Cancel,
            listFocus = focus.list,
            detailTop = HISTORY_DETAIL_TOP,
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
                    files = files,
                    today = today,
                    zone = clock.zone,
                    openFocus = openFocus,
                    onOpenFolder = { onEvent(HistoryEvent.OpenFile(it)) },
                    onAuthenticationRequired = onAuthenticationRequired,
                )
            },
        )
    }
}

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
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
) {
    val label = when (paging) {
        is HistoryPaging.Available -> R.string.tv_history_load_more
        is HistoryPaging.Loading -> R.string.tv_history_loading_more
        is HistoryPaging.Failed -> R.string.tv_state_try_again
        HistoryPaging.Complete -> null
    } ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paging is HistoryPaging.Failed) {
            Text(stringResource(R.string.tv_history_paging_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TvButton(
            onClick = {
                when (paging) {
                    is HistoryPaging.Available -> onNextPage()
                    is HistoryPaging.Failed -> onRetry()
                    is HistoryPaging.Loading, HistoryPaging.Complete -> Unit
                }
            },
            modifier = buttonModifier,
        ) {
            Text(stringResource(label))
        }
    }
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
private val HISTORY_DETAIL_TOP = 18.dp
private val STATE_BOTTOM_GAP = 3.dp
private val NOTICE_INSET = 12.dp
