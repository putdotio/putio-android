package io.putdotio.android.tv.history

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.tv.ROW_BLEED
import io.putdotio.android.tv.TV_LIST_ROW_META
import io.putdotio.android.tv.TV_LIST_ROW_META_MONO
import io.putdotio.android.tv.TvListRow
import io.putdotio.android.tv.TvListRowDot
import io.putdotio.android.tv.TvSectionLine
import io.putdotio.android.tv.TvSectionPill
import io.putdotio.android.tv.bringIntoComposition
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.withFullLineBox
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first

private sealed interface TvHistoryEntry {
    val key: Any

    data class Section(val label: String) : TvHistoryEntry {
        override val key: Any get() = SECTION_KEY
    }

    data class Header(val label: String, val day: TvHistoryDay) : TvHistoryEntry {
        override val key: Any get() = "header-$day"
    }

    data class Event(val item: HistoryItem) : TvHistoryEntry {
        override val key: Any get() = item.id.value
    }
}

@Composable
private fun historyEntries(items: List<HistoryItem>, today: LocalDate, zone: ZoneId): List<TvHistoryEntry> {
    val days = remember(items, zone) {
        items.fold(mutableListOf<Pair<TvHistoryDay, MutableList<HistoryItem>>>()) { groups, item ->
            val day = item.day(zone)
            val last = groups.lastOrNull()
            if (last?.first == day) last.second += item else groups += day to mutableListOf(item)
            groups
        }
    }
    val labels = days.map { (day, _) -> day.label(today) }
    return remember(days, labels) {
        buildList {
            days.forEachIndexed { index, (day, events) ->
                val label = labels[index]
                add(if (index == 0) TvHistoryEntry.Section(label) else TvHistoryEntry.Header(label, day))
                events.forEach { add(TvHistoryEntry.Event(it)) }
            }
        }
    }
}

private fun List<TvHistoryEntry>.firstRowFrom(top: Int): Int =
    indices.firstOrNull { it >= top && this[it] is TvHistoryEntry.Event } ?: -1

private fun List<TvHistoryEntry>.indexOfPick(id: Long?): Int =
    if (id == TV_HISTORY_CLEAR_PICK) {
        indexOfFirst { it is TvHistoryEntry.Section }
    } else {
        indexOfFirst { it is TvHistoryEntry.Event && it.item.id.value == id }
    }

private fun LazyListState.composedIndices(entries: List<TvHistoryEntry>): Set<Int> {
    val keys = layoutInfo.visibleItemsInfo.mapTo(HashSet()) { it.key }
    return entries.indices.filterTo(HashSet()) { entries[it].key in keys }
}

private suspend fun LazyListState.awaitMountRow(
    entries: List<TvHistoryEntry>,
    restoreIndex: Int,
    anchorIndex: Int,
): Boolean {
    val restoreKey = entries.getOrNull(restoreIndex)?.key
    if (restoreKey != null && layoutInfo.visibleItemsInfo.none { it.key == restoreKey }) {
        scrollToItem(restoreIndex)
    }
    val targetKey = restoreKey ?: entries.getOrNull(anchorIndex)?.key ?: return false
    snapshotFlow { layoutInfo.visibleItemsInfo.any { it.key == targetKey } }.first { it }
    return true
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvHistoryList(
    content: HistoryContent.Ready,
    restoreRowId: Long?,
    today: LocalDate,
    zone: ZoneId,
    focus: TvHistoryFocus,
    actions: TvHistoryListActions,
    modifier: Modifier = Modifier,
) {
    val listFocus = focus.list
    val paneHasFocus = focus.paneHasFocus
    val entries = historyEntries(content.items, today, zone)
    val anchorRow = remember { FocusRequester() }
    val lastRow = remember { FocusRequester() }
    val restoreRow = remember { FocusRequester() }
    val pagingHeldFocus = remember { mutableStateOf(false) }
    val handOffToLastRow = remember { mutableStateOf(false) }
    if (content.paging == HistoryPaging.Complete && pagingHeldFocus.value) {
        pagingHeldFocus.value = false
        handOffToLastRow.value = true
    }
    val listState = rememberLazyListState()
    val anchorIndex by remember(listState, entries) {
        derivedStateOf { entries.firstRowFrom(listState.firstVisibleItemIndex) }
    }
    val lastIndex = entries.lastIndex
    val restoreIndex = remember(entries) { entries.indexOfPick(restoreRowId) }
    LaunchedEffect(listState) {
        if (!listState.awaitMountRow(entries, restoreIndex, anchorIndex)) return@LaunchedEffect
        withFrameNanos {}
        if (paneHasFocus.value) (if (restoreIndex >= 0) restoreRow else listFocus).requestFocus()
    }
    LaunchedEffect(handOffToLastRow.value) {
        if (!handOffToLastRow.value) return@LaunchedEffect
        listState.bringIntoComposition(entries.last().key, lastIndex)
        withFrameNanos {}
        if (paneHasFocus.value) lastRow.requestFocus()
        handOffToLastRow.value = false
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(listFocus)
            .focusRestorer {
                val composed = listState.composedIndices(entries)
                when {
                    focus.clearHadFocus.value -> focus.clear
                    handOffToLastRow.value && lastIndex in composed -> lastRow
                    restoreIndex in composed -> restoreRow
                    else -> anchorRow
                }
            }
            .focusGroup()
            .testTag(TV_HISTORY_LIST_TAG),
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
            val requesters = Modifier
                .focusRequesterIf(index == anchorIndex, anchorRow)
                .focusRequesterIf(index == lastIndex, lastRow)
                .focusRequesterIf(index == restoreIndex, restoreRow)
            TvHistoryEntryItem(entry, zone, focus, actions, modifier = requesters)
        }
        if (content.paging != HistoryPaging.Complete) {
            item(key = PAGING_KEY) {
                TvHistoryPaging(
                    paging = content.paging,
                    onNextPage = actions.onNextPage,
                    onRetry = actions.onRetry,
                    buttonModifier = Modifier.onFocusChanged { pagingHeldFocus.value = it.isFocused },
                )
            }
        }
    }
}

@Composable
private fun TvHistoryEntryItem(
    entry: TvHistoryEntry,
    zone: ZoneId,
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
            zone = zone,
            onOpen = actions.onOpen,
            modifier = modifier.onFocusChanged { if (it.isFocused) actions.onRowFocused(entry.item) },
        )
    }
}

@Composable
private fun TvHistoryRow(
    item: HistoryItem,
    zone: ZoneId,
    onOpen: (HistoryFileId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = item.title()
    val fileId = item.kind.navigableFileId()
    val label = if (fileId == null) title else stringResource(R.string.tv_history_open_file, title)
    val kind = item.kind.kindLabel()?.let { stringResource(it) }
    val time = item.timeLabel(zone)
    TvListRow(
        glyph = item.kind.glyph(),
        title = title,
        onClick = { if (fileId != null) onOpen(fileId) },
        modifier = modifier
            .semantics { contentDescription = label }
            .testTag(TV_HISTORY_ROW_TAG),
        glyphSize = GLYPH,
        neutralGlyph = true,
    ) { secondary ->
        if (kind != null) {
            Text(
                kind,
                style = TV_LIST_ROW_META,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            TvListRowDot(secondary)
        }
        Text(time, style = TV_LIST_ROW_META_MONO, color = secondary, maxLines = 1, softWrap = false)
    }
}

private const val SECTION_KEY = "tv-history-section"
private const val PAGING_KEY = "tv-history-paging"
private val HEADER_LABEL = PutioTypeScale.tvLabel.withFullLineBox()
private val HEADER_TOP = 6.dp
private val HEADER_BOTTOM = 4.dp
private val GLYPH = 17.dp
