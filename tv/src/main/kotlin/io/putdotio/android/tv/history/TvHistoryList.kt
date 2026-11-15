package io.putdotio.android.tv.history

import android.content.res.Resources
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.RowText
import io.putdotio.android.design.rememberBuiltOffMain
import io.putdotio.android.design.rememberRowText
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.navigableFileId
import io.putdotio.android.tv.ProvideMiddleCuts
import io.putdotio.android.tv.ROW_BLEED
import io.putdotio.android.tv.TvListRow
import io.putdotio.android.tv.TvListRowMeta
import io.putdotio.android.tv.TvRowMeta
import io.putdotio.android.tv.TvRowMetaPart
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.first

internal sealed interface TvHistoryEntry {
    val key: Any

    data class Section(val label: String) : TvHistoryEntry {
        override val key: Any get() = SECTION_KEY
    }

    data class Header(val label: String, val day: TvHistoryDay) : TvHistoryEntry {
        override val key: Any get() = "header-$day"
    }

    data class Event(val item: HistoryItem, val title: String) : TvHistoryEntry {
        override val key: Any get() = item.id.value
    }
}

internal class TvHistoryStamp(val day: String, val time: String?)

internal class TvHistoryEntries(
    val items: List<HistoryItem>,
    val list: List<TvHistoryEntry>,
    private val stamps: Map<Long, TvHistoryStamp>,
) {
    fun stampOf(item: HistoryItem): TvHistoryStamp? = stamps[item.id.value]
}

@Composable
internal fun rememberHistoryEntries(items: List<HistoryItem>, today: LocalDate, zone: ZoneId): TvHistoryEntries? {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    return rememberBuiltOffMain(items, today, zone, resources, locale) {
        items.historyEntries(today, zone, resources, locale)
    }
}

private fun List<HistoryItem>.historyEntries(
    today: LocalDate,
    zone: ZoneId,
    resources: Resources,
    locale: Locale,
): TvHistoryEntries {
    val untitled = resources.getString(R.string.tv_history_no_title)
    val stamps = HashMap<Long, TvHistoryStamp>(size)
    val days = groupConsecutiveBy { it.day(zone) }
    val list = buildList {
        days.forEachIndexed { index, (day, events) ->
            val label = day.label(today, resources, locale)
            add(if (index == 0) TvHistoryEntry.Section(label) else TvHistoryEntry.Header(label, day))
            events.forEach {
                stamps[it.id.value] = TvHistoryStamp(label, it.timeLabel(zone).takeIf { day.date != null })
                add(TvHistoryEntry.Event(it, it.named() ?: untitled))
            }
        }
    }
    return TvHistoryEntries(this, list, stamps)
}

private fun <K> List<HistoryItem>.groupConsecutiveBy(keyOf: (HistoryItem) -> K): List<Pair<K, List<HistoryItem>>> =
    fold(mutableListOf<Pair<K, MutableList<HistoryItem>>>()) { groups, item ->
        val key = keyOf(item)
        val last = groups.lastOrNull()
        if (last?.first == key) last.second += item else groups += key to mutableListOf(item)
        groups
    }

private data class TvHistoryRowText(val meta: TvRowMeta, val label: String)

private val PENDING_ROW_TEXT = TvHistoryRowText(TvRowMeta.Pending, label = "")

@Composable
private fun rememberHistoryRowText(items: List<HistoryItem>, zone: ZoneId): RowText<HistoryItem, TvHistoryRowText> {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val untitled = stringResource(R.string.tv_history_no_title)
    return rememberRowText(
        items,
        zone,
        resources,
        locale,
        untitled,
        key = { it.id.value },
        placeholder = PENDING_ROW_TEXT,
    ) { it.rowText(zone, resources, untitled) }
}

private fun HistoryItem.rowText(zone: ZoneId, resources: Resources, untitled: String): TvHistoryRowText {
    val meta = TvRowMeta(
        listOfNotNull(
            kind.kindLabel()?.let { TvRowMetaPart(resources.getString(it), shrinks = true) },
            TvRowMetaPart(timeLabel(zone), mono = true),
        ),
    )
    return TvHistoryRowText(meta, rowLabel(named() ?: untitled, resources))
}

private fun HistoryItem.rowLabel(title: String, resources: Resources): String =
    if (kind.navigableFileId() == null) title else resources.getString(R.string.tv_history_open_file, title)

private fun Modifier.rowFocus(entry: TvHistoryEntry, requesters: TvItemRequesters): Modifier =
    if (entry is TvHistoryEntry.Event) focusRequester(requesters.of(entry.key)) else this

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
internal fun TvHistoryList(
    content: HistoryContent.Ready,
    history: TvHistoryEntries,
    restoreRowId: Long?,
    zone: ZoneId,
    focus: TvHistoryFocus,
    actions: TvHistoryListActions,
    modifier: Modifier = Modifier,
) {
    val listFocus = focus.list
    val paneHasFocus = focus.paneHasFocus
    val entries = history.list
    val titles = remember(entries) { entries.mapNotNull { (it as? TvHistoryEntry.Event)?.title } }
    val rowText = rememberHistoryRowText(content.items, zone)
    val rowFocus = remember { TvItemRequesters() }
    val lastRow = remember { FocusRequester() }
    val restoreRow = remember { FocusRequester() }
    val pagingHeldFocus = remember { mutableStateOf(false) }
    val handOffToLastRow = remember { mutableStateOf(false) }
    val built = history.items == content.items
    if (built && content.paging !is HistoryPaging.Failed && pagingHeldFocus.value) {
        pagingHeldFocus.value = false
        handOffToLastRow.value = true
    }
    val listState = rememberLazyListState()
    val focused = remember { TvFocusedItem() }
    val lastIndex = entries.lastIndex
    val restoreIndex = remember(entries) { entries.indexOfPick(restoreRowId) }
    val pendingEndIndex = lastIndex.takeIf { content.paging.isPending }
    TvAutoPaging(
        page = (content.paging as? HistoryPaging.Available).takeIf { built },
        onLoadMore = actions.onNextPage,
        nearEnd = { listState.isNearEnd(lastIndex) },
    )
    LaunchedEffect(listState) {
        val anchorIndex = entries.firstRowFrom(listState.firstVisibleItemIndex)
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
    ProvideMiddleCuts(titles) {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxWidth()
                .focusRequester(listFocus)
                .keepsHeldFocusInView(listState, focused)
                .focusRestorer {
                    when {
                        focus.clearHadFocus.value -> focus.clear
                        handOffToLastRow.value && listState.composes(entries.last().key) -> lastRow
                        listState.composes(entries.getOrNull(restoreIndex)?.key) -> restoreRow
                        else -> rowFocus.firstComposed(listState) { it is Long }
                    }
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
                TvHistoryEntryItem(entry, rowText, focus, actions, modifier = requesters)
            }
            if (content.paging != HistoryPaging.Complete) {
                item(key = PAGING_KEY, contentType = "paging") {
                    TvHistoryPaging(
                        paging = content.paging,
                        onRetry = actions.onRetry,
                        buttonModifier = Modifier
                            .tracksFocusedItem(focused, PAGING_KEY)
                            .onFocusChanged { pagingHeldFocus.value = it.isFocused },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvHistoryEntryItem(
    entry: TvHistoryEntry,
    rowText: RowText<HistoryItem, TvHistoryRowText>,
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
            text = rowText[entry.item],
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
    val resources = LocalResources.current
    val pendingLabel = remember(item, title, resources) { lazy { item.rowLabel(title, resources) } }
    TvListRow(
        glyph = item.kind.glyph(),
        title = title,
        onClick = { if (fileId != null) onOpen(fileId) },
        modifier = modifier
            .semantics { contentDescription = text.label.ifEmpty { pendingLabel.value } }
            .testTag(TV_HISTORY_ROW_TAG),
        glyphSize = GLYPH,
        neutralGlyph = true,
    ) { secondary -> TvListRowMeta(text.meta, secondary) }
}

private const val SECTION_KEY = "tv-history-section"
private const val PAGING_KEY = "tv-history-paging"
private val HEADER_LABEL = PutioTypeScale.tvLabel.withFullLineBox()
private val HEADER_TOP = 6.dp
private val HEADER_BOTTOM = 4.dp
private val GLYPH = 17.dp
