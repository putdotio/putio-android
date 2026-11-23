package io.putdotio.android.tv.search

import androidx.annotation.StringRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.Identity
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.RowText
import io.putdotio.android.design.rememberRowText
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchTotal
import io.putdotio.android.tv.TvListSkeleton
import io.putdotio.android.tv.ProvideMiddleCuts
import io.putdotio.android.tv.TvAutoPaging
import io.putdotio.android.tv.TvFocusedItem
import io.putdotio.android.tv.TvItemRequesters
import io.putdotio.android.tv.TvPaneFocusOwner
import io.putdotio.android.tv.bringIntoComposition
import io.putdotio.android.tv.composes
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.holdAtPendingEnd
import io.putdotio.android.tv.isNearEnd
import io.putdotio.android.tv.keepsHeldFocusInView
import io.putdotio.android.tv.paneSection
import io.putdotio.android.tv.tracksFocusedItem
import io.putdotio.android.tv.files.TvFilesRow
import io.putdotio.android.tv.files.TvFilesRowPlace
import io.putdotio.android.tv.files.TvFilesRowText
import io.putdotio.android.tv.files.rowText

internal const val TV_SEARCH_RESULTS_TAG = "tv-search-results"

internal class TvSearchRows(
    val folderNames: Map<FilesItemId, String>,
    val rootName: String,
    val onResult: (FilesItem) -> Unit,
    val onActions: ((FilesItem) -> Unit)?,
    val onFocused: (FilesItem) -> Unit,
    val detail: TvSearchRowDetail,
)

internal class TvSearchRowDetail(
    val id: Long?,
    val row: FocusRequester,
)

internal fun TvSearchRows.place(item: FilesItem): TvFilesRowPlace.SearchResult =
    searchPlace(item, folderNames, rootName)

private fun searchPlace(
    item: FilesItem,
    folderNames: Map<FilesItemId, String>,
    rootName: String,
): TvFilesRowPlace.SearchResult =
    TvFilesRowPlace.SearchResult(
        item.parentId?.let { parent -> if (parent == FilesFolder.Root.id) rootName else folderNames[parent] },
    )

internal class TvSearchGroups(items: List<FilesItem>, val total: SearchTotal? = null) {
    val folders: List<FilesItem> = items.filter(FilesItem::isFolder)
    val files: List<FilesItem> = items.filterNot(FilesItem::isFolder)
    val ordered: List<FilesItem> = folders + files

    fun lazyIndexOf(id: Long): Int {
        val index = ordered.indexOfFirst { it.id.value == id }
        if (index < 0) return -1
        val totalLabel = if (total == null) 0 else 1
        val folderLabel = if (folders.isEmpty()) 0 else 1
        val before = totalLabel + folderLabel
        return if (index < folders.size) index + before else index + before + 1
    }
}

@Composable
internal fun rememberTvSearchRowText(
    groups: TvSearchGroups,
    folderNames: Map<FilesItemId, String>,
    rootName: String,
): RowText<FilesItem, TvFilesRowText> {
    val context = LocalContext.current
    val currentNames by rememberUpdatedState(folderNames)
    val currentRoot by rememberUpdatedState(rootName)
    return rememberRowText(
        groups.ordered,
        context,
        key = { it.id.value },
        placeholder = TvFilesRowText.Pending,
        refresh = Identity(folderNames) to rootName,
    ) {
        it.rowText(context, searchPlace(it, currentNames, currentRoot), System.currentTimeMillis())
    }
}

@Composable
internal fun TvSearchResultList(
    groups: TvSearchGroups?,
    rowText: RowText<FilesItem, TvFilesRowText>,
    paging: SearchPaging,
    pagingEnabled: Boolean,
    restoreRowId: Long?,
    rows: TvSearchRows,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    owner: TvPaneFocusOwner,
    listFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    if (groups == null) {
        TvListSkeleton(
            modifier
                .focusRequester(listFocus)
                .onFocusChanged { if (it.isFocused) owner.enter(listFocus) }
                .focusable(),
        )
        return
    }
    TvSearchResultRows(
        groups, rowText, paging, pagingEnabled, restoreRowId, rows, onNextPage, onRetry, owner, listFocus, modifier,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvSearchResultRows(
    groups: TvSearchGroups,
    rowText: RowText<FilesItem, TvFilesRowText>,
    paging: SearchPaging,
    pagingEnabled: Boolean,
    restoreRowId: Long?,
    rows: TvSearchRows,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    owner: TvPaneFocusOwner,
    listFocus: FocusRequester,
    modifier: Modifier,
) {
    val items = groups.ordered
    val names = remember(Identity(items)) { items.map { it.name } }
    val rowFocus = remember { TvItemRequesters() }
    val lastRow = remember { FocusRequester() }
    val pagingHeldFocus = remember { mutableStateOf(false) }
    val handOffToLastRow = remember { mutableStateOf(false) }
    if (paging !is SearchPaging.Failed && pagingHeldFocus.value) {
        pagingHeldFocus.value = false
        handOffToLastRow.value = true
    }
    val listState = rememberLazyListState()
    val focused = remember { TvFocusedItem() }
    val restoreRow = remember { FocusRequester() }
    val lastLazyIndex = remember(groups) { groups.lazyIndexOf(items.last().id.value) }
    TvAutoPaging(
        page = (paging as? SearchPaging.Available)?.takeIf { pagingEnabled },
        onLoadMore = onNextPage,
        nearEnd = { listState.isNearEnd(lastLazyIndex) },
    )
    LaunchedEffect(listState) { listState.placeFocusOnMount(groups, restoreRowId, owner, listFocus, restoreRow) }
    TvSearchLastRowHandOff(groups, handOffToLastRow, listState, owner, listFocus, lastRow)
    ProvideMiddleCuts(names) {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxWidth()
                .paneSection(owner, listFocus)
                .focusRequester(listFocus)
                .keepsHeldFocusInView(listState, focused)
                .focusRestorer {
                    val lastId = items.last().id.value
                    lastRow.takeIf { handOffToLastRow.value && listState.composes(lastId) }
                        ?: rows.detail.row.takeIf { listState.composes(rows.detail.id) }
                        ?: restoreRow.takeIf { listState.composes(restoreRowId) }
                        ?: rowFocus.firstComposed(listState) { it is Long }
                }
                .focusGroup()
                .testTag(TV_SEARCH_RESULTS_TAG),
        ) {
            val requesters = TvSearchRowRequesters(
                focused,
                rowFocus,
                last = TvSearchRowDetail(items.last().id.value, lastRow),
                restore = TvSearchRowDetail(restoreRowId, restoreRow),
                pendingEnd = paging.isPending,
            )
            val row: @Composable (FilesItem) -> Unit = { item ->
                TvSearchResultRow(item, rowText[item], rows, requesters)
            }
            groups.total?.let { total ->
                item(key = "search-total", contentType = "total") { TvSearchTotal(total) }
            }
            val untitled = groups.total == null
            resultGroup(R.string.tv_search_group_folders, "folders", groups.folders, first = untitled, row = row)
            resultGroup(
                R.string.tv_search_group_files,
                "files",
                groups.files,
                first = untitled && groups.folders.isEmpty(),
                row = row,
            )
            if (paging != SearchPaging.Complete) {
                item(key = TV_SEARCH_PAGING_KEY, contentType = "paging") {
                    TvSearchPaging(
                        paging = paging,
                        onRetry = onRetry,
                        buttonModifier = Modifier
                            .tracksFocusedItem(focused, TV_SEARCH_PAGING_KEY)
                            .onFocusChanged { pagingHeldFocus.value = it.isFocused },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSearchLastRowHandOff(
    groups: TvSearchGroups,
    handOffToLastRow: MutableState<Boolean>,
    listState: LazyListState,
    owner: TvPaneFocusOwner,
    listFocus: FocusRequester,
    lastRow: FocusRequester,
) {
    val items = groups.ordered
    LaunchedEffect(handOffToLastRow.value, items.lastOrNull()?.id?.value) {
        if (!handOffToLastRow.value) return@LaunchedEffect
        val last = items.lastOrNull()?.id?.value
        if (last != null) {
            listState.bringIntoComposition(last, groups.lazyIndexOf(last))
            withFrameNanos {}
            if (owner.owns(listFocus) && !lastRow.requestFocus() && !listFocus.requestFocus()) owner.focusHome()
        } else if (owner.owns(listFocus) && !listFocus.requestFocus()) {
            owner.focusHome()
        }
        handOffToLastRow.value = false
    }
}

private suspend fun LazyListState.placeFocusOnMount(
    groups: TvSearchGroups,
    restoreRowId: Long?,
    owner: TvPaneFocusOwner,
    listFocus: FocusRequester,
    restoreRow: FocusRequester,
) {
    val index = restoreRowId?.let(groups::lazyIndexOf) ?: -1
    if (restoreRowId == null || index < 0) {
        withFrameNanos {}
        if (owner.owns(listFocus)) listFocus.requestFocus()
        return
    }
    bringIntoComposition(restoreRowId, index)
    owner.enter(listFocus)
    withFrameNanos {}
    if (owner.hasFocus) restoreRow.requestFocus()
}

private class TvSearchRowRequesters(
    val focused: TvFocusedItem,
    val rows: TvItemRequesters,
    val last: TvSearchRowDetail,
    val restore: TvSearchRowDetail,
    val pendingEnd: Boolean,
)

private val SearchPaging.isPending: Boolean
    get() = this is SearchPaging.Available || this is SearchPaging.Loading

@Composable
private fun TvSearchResultRow(
    item: FilesItem,
    text: TvFilesRowText,
    rows: TvSearchRows,
    requesters: TvSearchRowRequesters,
) {
    val id = item.id.value
    TvFilesRow(
        item = item,
        text = text,
        onClick = { rows.onResult(item) },
        place = rows.place(item),
        onActions = rows.onActions?.let { open -> { open(item) } },
        modifier = Modifier
            .onFocusChanged { if (it.isFocused) rows.onFocused(item) }
            .tracksFocusedItem(requesters.focused, id)
            .focusRequester(requesters.rows.of(id))
            .focusRequesterIf(id == requesters.last.id, requesters.last.row)
            .holdAtPendingEnd(requesters.pendingEnd && id == requesters.last.id)
            .focusRequesterIf(id == requesters.restore.id, requesters.restore.row)
            .focusRequesterIf(id == rows.detail.id, rows.detail.row),
    )
}

private fun LazyListScope.resultGroup(
    @StringRes title: Int,
    key: String,
    items: List<FilesItem>,
    first: Boolean,
    row: @Composable (FilesItem) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "group-$key", contentType = "header") {
        TvSearchGroupLabel(stringResource(title), first)
    }
    items(items, key = { it.id.value }, contentType = { "row" }) { row(it) }
}

@Composable
private fun TvSearchTotal(total: SearchTotal) {
    val shown = total.shown(LocalConfiguration.current.locales[0])
    Text(
        text = pluralStringResource(R.plurals.tv_search_total, total.count, shown),
        style = PutioTypeScale.tvLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LABEL_INSET)
            .height(TV_SEARCH_LABEL_HEIGHT),
    )
}

@Composable
private fun TvSearchGroupLabel(title: String, first: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LABEL_INSET, end = LABEL_INSET, top = if (first) 0.dp else LABEL_SPACE_ABOVE)
            .padding(bottom = TV_SEARCH_LABEL_GAP + LABEL_BASELINE_SLACK)
            .height(TV_SEARCH_LABEL_HEIGHT)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text(title, style = PutioTypeScale.tvLabel, color = color)
    }
}

internal val TV_SEARCH_LABEL_HEIGHT = 16.dp
internal val TV_SEARCH_LABEL_GAP = 4.dp
private val LABEL_INSET = 12.dp
private val LABEL_SPACE_ABOVE = 8.dp
private val LABEL_BASELINE_SLACK = 0.5.dp
internal const val TV_SEARCH_PAGING_KEY = "tv-search-paging"
