package io.putdotio.android.tv.history

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.Built
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.navigableFileId
import io.putdotio.android.tv.ProvideMiddleCuts
import io.putdotio.android.tv.ROW_BLEED
import io.putdotio.android.tv.TvListRow
import io.putdotio.android.tv.TvListRowMeta
import io.putdotio.android.tv.TvSectionLine
import io.putdotio.android.tv.TvSectionPill
import io.putdotio.android.tv.bringIntoComposition
import io.putdotio.android.tv.TvAutoPaging
import io.putdotio.android.tv.TvFocusedItem
import io.putdotio.android.tv.TvItemRequesters
import io.putdotio.android.tv.composes
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.holdAtPendingEnd
import io.putdotio.android.tv.isNearEnd
import io.putdotio.android.tv.keepsHeldFocusInView
import io.putdotio.android.tv.tracksFocusedItem
import io.putdotio.android.tv.withFullLineBox
import kotlinx.coroutines.flow.first

private fun Modifier.rowFocus(entry: TvHistoryEntry, requesters: TvItemRequesters): Modifier =
    if (entry is TvHistoryEntry.Event) focusRequester(requesters.of(entry.key)) else this

private class TvLastRowHandOff {
    val pagingHeldFocus = mutableStateOf(false)
    val pending = mutableStateOf(false)
    var built = false

    fun latchWhenPagingLeaves(paging: HistoryPaging) {
        if (paging !is HistoryPaging.Failed && pagingHeldFocus.value) {
            pagingHeldFocus.value = false
            pending.value = true
        }
    }

    fun focusTaken() {
        if (!built) pending.value = false
    }
}

@Composable
private fun rememberLastRowHandOff(built: Boolean, paging: HistoryPaging): TvLastRowHandOff {
    val handOff = remember { TvLastRowHandOff() }
    SideEffect { handOff.built = built }
    LaunchedEffect(paging !is HistoryPaging.Failed, handOff.pagingHeldFocus.value) {
        handOff.latchWhenPagingLeaves(paging)
    }
    return handOff
}

@Composable
private fun HandOffToLastRow(
    handOff: TvLastRowHandOff,
    built: Boolean,
    listState: LazyListState,
    entries: List<TvHistoryEntry>,
    lastRow: FocusRequester,
    mayTakeFocus: () -> Boolean,
) {
    LaunchedEffect(handOff.pending.value, built, entries.size, entries.lastOrNull()?.key) {
        if (!handOff.pending.value || !built) return@LaunchedEffect
        listState.bringIntoComposition(entries.last().key, entries.lastIndex)
        withFrameNanos {}
        if (mayTakeFocus()) lastRow.requestFocus()
        handOff.pending.value = false
    }
}

private fun HistoryPaging.nextPageWhen(built: Boolean): HistoryPaging.Available? =
    (this as? HistoryPaging.Available)?.takeIf { built }

private val HistoryPaging.isPending: Boolean
    get() = this is HistoryPaging.Available || this is HistoryPaging.Loading

private fun List<TvHistoryEntry>.firstRowFrom(top: Int): Int =
    indices.firstOrNull { it >= top && this[it] is TvHistoryEntry.Event } ?: -1

private fun List<TvHistoryEntry>.indexOfPick(id: Long?): Int =
    if (id == TV_HISTORY_CLEAR_PICK) {
        indexOfFirst { it is TvHistoryEntry.Section }
    } else {
        indexOfFirst { it is TvHistoryEntry.Event && it.item.id.value == id }
    }

private class TvHistoryRowRequesters(
    val rows: TvItemRequesters,
    val last: FocusRequester,
    val restore: FocusRequester,
)

private fun LazyListState.historyRestoreTarget(
    focus: TvHistoryFocus,
    requesters: TvHistoryRowRequesters,
    lastKey: Any?,
    liveKey: Any?,
    restoreKey: Any?,
): FocusRequester = when {
    focus.clearHadFocus.value -> focus.clear
    lastKey != null && composes(lastKey) -> requesters.last
    liveKey != null && composes(liveKey) -> requesters.rows.of(liveKey)
    composes(restoreKey) -> requesters.restore
    else -> requesters.rows.firstComposed(this) { it is Long }
}

private suspend fun LazyListState.awaitMountRow(
    entries: List<TvHistoryEntry>,
    restoreIndex: Int,
    anchorIndex: Int,
): Boolean {
    val restoreKey = entries.getOrNull(restoreIndex)?.key
    val targetKey = restoreKey ?: entries.getOrNull(anchorIndex)?.key ?: return false
    if (restoreKey != null) {
        bringIntoComposition(restoreKey, restoreIndex)
    } else {
        snapshotFlow { composes(targetKey) }.first { it }
    }
    return true
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
@NonSkippableComposable
internal fun TvHistoryList(
    content: HistoryContent.Ready,
    history: Built<List<HistoryItem>, TvHistoryEntries>,
    restoreRowId: Long?,
    focus: TvHistoryFocus,
    actions: TvHistoryListActions,
    modifier: Modifier = Modifier,
) {
    val listFocus = focus.list
    val paneHasFocus = focus.paneHasFocus
    val entries = history.value.list
    val rowFocus = remember { TvItemRequesters() }
    val lastRow = remember { FocusRequester() }
    val restoreRow = remember { FocusRequester() }
    val lastFocused = remember { TvFocusedItem() }
    val built = history.isBuiltFrom(content.items)
    val handOff = rememberLastRowHandOff(built, content.paging)
    val pagingFailed by rememberUpdatedState(content.paging is HistoryPaging.Failed)
    val entryActions = remember(actions, handOff, lastFocused) {
        TvHistoryListActions(
            onOpen = actions.onOpen,
            onClear = actions.onClear,
            onRowFocused = {
                handOff.focusTaken()
                lastFocused.key = it.id.value
                actions.onRowFocused(it)
            },
            onClearFocused = {
                handOff.focusTaken()
                actions.onClearFocused()
            },
            onNextPage = actions.onNextPage,
            onRetry = actions.onRetry,
        )
    }
    val listState = rememberLazyListState()
    val focused = remember { TvFocusedItem() }
    val lastIndex = entries.lastIndex
    val restoreIndex = remember(history.value) { entries.indexOfPick(restoreRowId) }
    val pendingEndIndex = lastIndex.takeIf { content.paging.isPending }
    TvAutoPaging(
        page = content.paging.nextPageWhen(built),
        onLoadMore = actions.onNextPage,
        nearEnd = { listState.isNearEnd(lastIndex) },
    )
    LaunchedEffect(listState) {
        val anchorIndex = entries.firstRowFrom(listState.firstVisibleItemIndex)
        if (!listState.awaitMountRow(entries, restoreIndex, anchorIndex)) return@LaunchedEffect
        withFrameNanos {}
        if (paneHasFocus.value) (if (restoreIndex >= 0) restoreRow else listFocus).requestFocus()
    }
    HandOffToLastRow(handOff, built, listState, entries, lastRow) { paneHasFocus.value }
    ProvideMiddleCuts(history.value.titles) {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxWidth()
                .focusRequester(listFocus)
                .keepsHeldFocusInView(listState, focused)
                .focusRestorer {
                    listState.historyRestoreTarget(
                        focus = focus,
                        requesters = TvHistoryRowRequesters(rowFocus, lastRow, restoreRow),
                        lastKey = entries.last().key.takeIf { built && handOff.pending.value },
                        liveKey = focused.key?.takeIf { it is Long } ?: lastFocused.key,
                        restoreKey = entries.getOrNull(restoreIndex)?.key,
                    )
                }
                .focusGroup()
                .testTag(TV_HISTORY_LIST_TAG),
        ) {
            itemsIndexed(
                entries,
                key = { _, entry -> entry.key },
                contentType = { _, entry -> entry.javaClass },
            ) { index, entry ->
                val requesters = Modifier
                    .tracksFocusedItem(focused, entry.key)
                    .rowFocus(entry, rowFocus)
                    .focusRequesterIf(index == lastIndex, lastRow)
                    .holdAtPendingEnd(index == pendingEndIndex)
                    .focusRequesterIf(index == restoreIndex, restoreRow)
                TvHistoryEntryItem(entry, focus, entryActions, modifier = requesters)
            }
            if (content.paging != HistoryPaging.Complete) {
                item(key = PAGING_KEY, contentType = "paging") {
                    TvHistoryPaging(
                        paging = content.paging,
                        onRetry = actions.onRetry,
                        buttonModifier = Modifier
                            .tracksFocusedItem(focused, PAGING_KEY)
                            .onFocusChanged {
                                if (it.isFocused || pagingFailed) handOff.pagingHeldFocus.value = it.isFocused
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvHistoryEntryItem(
    entry: TvHistoryEntry,
    focus: TvHistoryFocus,
    actions: TvHistoryListActions,
    modifier: Modifier = Modifier,
) {
    when (entry) {
        is TvHistoryEntry.Section -> TvSectionLine(
            pill = {
                TvSectionPill(
                    icon = R.drawable.ic_ph_trash,
                    label = stringResource(R.string.tv_history_clear),
                    onClick = actions.onClear,
                    modifier = modifier
                        .focusRequester(focus.clear)
                        .onFocusChanged { if (it.isFocused) actions.onClearFocused() },
                )
            },
        ) { Text(entry.label, style = PutioTypeScale.tvLabel) }
        is TvHistoryEntry.Header -> Text(
            text = entry.label,
            style = HEADER_LABEL,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = ROW_BLEED, top = HEADER_TOP, bottom = HEADER_BOTTOM),
        )
        is TvHistoryEntry.Event -> TvHistoryRow(
            item = entry.item,
            title = entry.title,
            text = entry.text,
            onOpen = actions.onOpen,
            modifier = modifier.onFocusChanged { if (it.isFocused) actions.onRowFocused(entry.item) },
        )
    }
}

@Composable
private fun TvHistoryRow(
    item: HistoryItem,
    title: String,
    text: TvHistoryRowText,
    onOpen: (HistoryFileId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fileId = item.kind.navigableFileId()
    TvListRow(
        glyph = item.kind.glyph(),
        title = title,
        onClick = { if (fileId != null) onOpen(fileId) },
        modifier = modifier
            .semantics { contentDescription = text.label }
            .testTag(TV_HISTORY_ROW_TAG),
        glyphSize = GLYPH,
        neutralGlyph = true,
    ) { secondary -> TvListRowMeta(text.meta, secondary) }
}

private const val PAGING_KEY = "tv-history-paging"
private val HEADER_LABEL = PutioTypeScale.tvLabel.withFullLineBox()
private val HEADER_TOP = 6.dp
private val HEADER_BOTTOM = 4.dp
private val GLYPH = 17.dp
