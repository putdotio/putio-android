package io.putdotio.android.tv.trash

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashState
import io.putdotio.android.tv.ROW_BLEED
import io.putdotio.android.tv.TV_LIST_WIDTH
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvButtonSize
import io.putdotio.android.tv.TvButtonStyle
import io.putdotio.android.tv.TvDetailSkeleton
import io.putdotio.android.tv.TvEmptyState
import io.putdotio.android.tv.TvListDetail
import io.putdotio.android.tv.TvListSkeleton
import io.putdotio.android.tv.TvLoadFailedState
import io.putdotio.android.tv.TvPaneFocusOwner
import io.putdotio.android.tv.TvPickedRow
import io.putdotio.android.tv.files.TvFolderHeading
import io.putdotio.android.tv.files.tvLoadFailedMessage
import io.putdotio.android.tv.monoNumber
import io.putdotio.android.tv.paneSection
import io.putdotio.android.tv.refocusAfterDialog
import io.putdotio.android.tv.rememberTvPaneHasFocus
import io.putdotio.android.tv.withFullLineBox

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvTrashScreen(
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
    modifier: Modifier = Modifier,
    sessionKey: Any? = null,
) = key(sessionKey) {
    val content = state.content
    val loaded = content as? TrashContent.Loaded
    val hasRows = loaded?.isKnownEmpty == false
    val focus = remember { TvTrashFocus() }
    val paneHasFocus = rememberTvPaneHasFocus()
    val entryTarget = remember { mutableStateOf(focus.restoreAll) }
    val rowsPresent = rememberUpdatedState(hasRows)
    val pickedRow = rememberSaveable(saver = TvPickedRow.Saver) { TvPickedRow() }
    val restoreRowId = remember { pickedRow.id }
    val lastSection = rememberSaveable { mutableStateOf<Long?>(null) }
    val owner = remember {
        TvPaneFocusOwner(entryTarget, paneHasFocus, lastSection, onEnter = pickedRow.keptOnlyBy(focus.list)) {
            if (rowsPresent.value) focus.list else focus.restoreAll
        }
    }
    val headerOwnsFocus = content.isLoadingOrKnownEmpty
    val dialogOpen = state.confirmation != null || state.actionConfirmation != null
    val dialogShowing = rememberUpdatedState(dialogOpen)
    val refreshedOnOpen = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!refreshedOnOpen.value) {
            refreshedOnOpen.value = true
            if (content is TrashContent.Loaded && !dialogOpen) onEvent(TrashEvent.Refresh)
        }
    }
    LaunchedEffect(headerOwnsFocus) {
        if (headerOwnsFocus && mayPlaceFocus(paneHasFocus, dialogShowing)) focus.restoreAll.requestFocus()
    }
    val dialogWasOpen = remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) { refocusAfterDialog(dialogOpen, dialogWasOpen, paneHasFocus, entryTarget) }
    owner.RefocusAfterLeave(blocked = dialogShowing)
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
        TvTrashHeader(
            onRestoreAll = { onEvent(TrashEvent.SelectRestoreAll) },
            onEmpty = { onEvent(TrashEvent.SelectEmpty) },
            showsFocus = !dialogOpen,
            restoreAllModifier = Modifier.keyedPaneSection("restoreAll", owner, focus.restoreAll),
            emptyModifier = Modifier.keyedPaneSection("empty", owner, focus.empty),
        )
        when {
            content is TrashContent.Error -> {
                EntryTarget(entryTarget, focus.retry)
                TvTrashStateNotices(state, onEvent, owner)
                TvLoadFailedState(
                    failure = content.failure,
                    headline = stringResource(R.string.tv_trash_error_title),
                    message = content.failure.tvLoadFailedMessage(),
                    onRetry = { onEvent(TrashEvent.Retry) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = STATE_BOTTOM_GAP)
                        .paneSection(owner, focus.retry),
                    actionFocus = focus.retry,
                    claimFocus = paneHasFocus.value,
                )
            }
            loaded != null && !hasRows -> {
                EntryTarget(entryTarget, focus.restoreAll)
                TvTrashStateNotices(state, onEvent, owner)
                TvEmptyState(
                    glyph = PutioEmptyGlyph.EmptyFolder,
                    headline = stringResource(R.string.tv_trash_empty_title),
                    message = stringResource(R.string.tv_trash_empty_message),
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = STATE_BOTTOM_GAP),
                )
            }
            else -> {
                EntryTarget(entryTarget, if (loaded != null) focus.list else focus.restoreAll)
                CompositionLocalProvider(LocalTrashToday provides rememberTrashToday()) {
                    TvTrashListDetail(
                        state = state,
                        onEvent = onEvent,
                        focus = focus,
                        listFocus = TvTrashListFocus(owner, focus.list, paneHasFocus, dialogShowing),
                        pickedRow = pickedRow,
                        restoreRowId = restoreRowId,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    TvTrashConfirmations(state, onEvent)
    LaunchedEffect(owner) { owner.restoreLastSection() }
}

@Composable
private fun TvTrashListDetail(
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
    focus: TvTrashFocus,
    listFocus: TvTrashListFocus,
    pickedRow: TvPickedRow,
    restoreRowId: Long?,
    modifier: Modifier = Modifier,
) {
    val loaded = state.content as? TrashContent.Loaded
    var detailId by rememberSaveable { mutableStateOf(pickedRow.id) }
    var rowHasFocus by remember { mutableStateOf(false) }
    var detailHasFocus by remember { mutableStateOf(false) }
    val items = loaded?.items.orEmpty()
    val detailItem = items.firstOrNull { it.id.value == detailId }
        ?: items.firstOrNull { it.id.value == pickedRow.id }
        ?: items.firstOrNull()
    TvListDetail(
        modifier = modifier,
        detailFocus = focus.restore,
        listFocus = focus.list,
        onDetailFocusChanged = { detailHasFocus = it },
        detailTop = DETAIL_TOP,
        list = {
            TvTrashInfo()
            TvTrashNotices(state, onEvent, listFocus.owner)
            if (loaded == null) {
                TvListSkeleton()
            } else {
                TvTrashList(
                    content = loaded,
                    restoreRowId = restoreRowId,
                    onChoose = { if (state.canRestore(it.id)) onEvent(TrashEvent.SelectRestore(it.id)) },
                    onRowFocusChanged = { item, focused ->
                        rowHasFocus = focused
                        if (focused) {
                            pickedRow.pick(item.id.value)
                            detailId = item.id.value
                        }
                    },
                    onNextPage = { onEvent(TrashEvent.LoadNextPage) },
                    onRetry = { onEvent(TrashEvent.Retry) },
                    focus = listFocus,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        detail = {
            when {
                loaded == null -> TvDetailSkeleton()
                detailItem != null -> TvTrashDetail(
                    item = detailItem,
                    actions = TvTrashDetailActions(
                        canRestore = state.canRestore(detailItem.id),
                        canDelete = state.canDelete(detailItem.id),
                        rowHasFocus = rowHasFocus,
                        detailHasFocus = detailHasFocus,
                        onRestore = { onEvent(TrashEvent.SelectRestore(detailItem.id)) },
                        onDelete = { onEvent(TrashEvent.SelectDelete(detailItem.id)) },
                        showsFocus = !listFocus.dialogShowing.value,
                        restoreModifier = Modifier.keyedPaneSection("restore", listFocus.owner, focus.restore),
                        deleteModifier = Modifier.keyedPaneSection("delete", listFocus.owner, focus.delete),
                    ),
                )
            }
        },
    )
}

internal class TvTrashListFocus(
    val owner: TvPaneFocusOwner,
    val list: FocusRequester,
    val paneHasFocus: State<Boolean>,
    val dialogShowing: State<Boolean>,
)

internal data class TvTrashDetailActions(
    val canRestore: Boolean,
    val canDelete: Boolean,
    val rowHasFocus: Boolean,
    val detailHasFocus: Boolean,
    val onRestore: () -> Unit,
    val onDelete: () -> Unit,
    val showsFocus: Boolean,
    val restoreModifier: Modifier,
    val deleteModifier: Modifier,
)

private class TvTrashFocus {
    val restoreAll = FocusRequester()
    val empty = FocusRequester()
    val list = FocusRequester()
    val retry = FocusRequester()
    val restore = FocusRequester()
    val delete = FocusRequester()
}

@Composable
private fun EntryTarget(entryTarget: MutableState<FocusRequester>, target: FocusRequester) {
    DisposableEffect(target) {
        entryTarget.value = target
        onDispose {}
    }
}

@Composable
private fun Modifier.keyedPaneSection(name: String, owner: TvPaneFocusOwner, requester: FocusRequester): Modifier =
    key(name) { paneSection(owner, requester) }.focusRequester(requester)

private val TrashContent.isLoadingOrKnownEmpty: Boolean
    get() = this is TrashContent.Loading || (this is TrashContent.Loaded && isKnownEmpty)

@Composable
private fun TvTrashHeader(
    onRestoreAll: () -> Unit,
    onEmpty: () -> Unit,
    showsFocus: Boolean,
    modifier: Modifier = Modifier,
    restoreAllModifier: Modifier = Modifier,
    emptyModifier: Modifier = Modifier,
) {
    Box(modifier = modifier.height(HEADER_HEIGHT)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            TvFolderHeading(
                name = stringResource(R.string.tv_trash_title),
                crumb = stringResource(R.string.tv_destination_account),
            )
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(HEADER_BUTTON_GAP),
                modifier = Modifier
                    .padding(bottom = HEADER_BUTTON_LIFT)
                    .focusGroup(),
            ) {
                TvTrashHeaderButton(
                    icon = R.drawable.ic_ph_arrow_u_up_left,
                    label = stringResource(R.string.tv_trash_restore_all),
                    onClick = onRestoreAll,
                    style = TvButtonStyle.Tonal,
                    showsFocus = showsFocus,
                    modifier = restoreAllModifier,
                )
                TvTrashHeaderButton(
                    icon = R.drawable.ic_ph_trash,
                    label = stringResource(R.string.tv_trash_empty_action),
                    onClick = onEmpty,
                    style = TvButtonStyle.TonalDanger,
                    showsFocus = showsFocus,
                    modifier = emptyModifier,
                )
            }
        }
    }
}

@Composable
private fun TvTrashHeaderButton(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    style: TvButtonStyle,
    showsFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    TvButton(
        onClick = onClick,
        modifier = modifier,
        style = style,
        size = TvButtonSize.Header,
        showsFocus = showsFocus,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(TvButtonSize.Header.icon))
        Text(label, style = HEADER_BUTTON_LABEL, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun TvTrashInfo() {
    val info = stringResource(R.string.tv_trash_info)
    Text(
        text = remember(info) { monoNumber(info, RETENTION_DAYS) },
        style = INFO_LABEL,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = ROW_BLEED, end = ROW_BLEED, bottom = INFO_GAP),
    )
}

@Composable
private fun TvTrashStateNotices(
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
    owner: TvPaneFocusOwner,
) {
    Column(modifier = Modifier.offset(x = -ROW_BLEED).width(TV_LIST_WIDTH)) {
        TvTrashNotices(state, onEvent, owner)
    }
}

private const val RETENTION_DAYS = "14"
private val HEADER_HEIGHT = 76.dp
private val HEADER_BUTTON_GAP = 8.dp
private val HEADER_BUTTON_LIFT = 1.dp
private val HEADER_BUTTON_LABEL = PutioTypeScale.tvButton.copy(fontSize = 12.sp)
private val INFO_GAP = 4.dp
private val INFO_LABEL = PutioTypeScale.tvLabel.withFullLineBox()
private val DETAIL_TOP = 26.dp
private val STATE_BOTTOM_GAP = 3.dp
