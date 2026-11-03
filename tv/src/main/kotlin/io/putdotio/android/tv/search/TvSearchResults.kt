package io.putdotio.android.tv.search

import androidx.annotation.StringRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.tv.TvPaneFocusOwner
import io.putdotio.android.tv.bringIntoComposition
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.paneSection
import io.putdotio.android.tv.files.TvFilesRow
import io.putdotio.android.tv.files.TvFilesRowPlace
import kotlinx.coroutines.flow.first

internal const val TV_SEARCH_RESULTS_TAG = "tv-search-results"

internal class TvSearchRows(
    val folderName: (FilesItemId) -> String?,
    val onResult: (FilesItem) -> Unit,
    val onActions: ((FilesItem) -> Unit)?,
    val onFocused: (FilesItem) -> Unit,
    val detailId: Long?,
    val detailRow: FocusRequester,
)

internal fun TvSearchRows.place(item: FilesItem): TvFilesRowPlace.SearchResult =
    TvFilesRowPlace.SearchResult(
        item.parentId?.let(folderName),
    )

internal fun folderNameOf(names: Map<FilesItemId, String>, rootName: String): (FilesItemId) -> String? =
    { parent -> if (parent == FilesFolder.Root.id) rootName else names[parent] }

internal class TvSearchGroups(items: List<FilesItem>) {
    val folders: List<FilesItem> = items.filter(FilesItem::isFolder)
    val files: List<FilesItem> = items.filterNot(FilesItem::isFolder)
    val ordered: List<FilesItem> = folders + files

    fun lazyIndexOf(id: Long): Int {
        val index = ordered.indexOfFirst { it.id.value == id }
        if (index < 0) return -1
        val folderLabel = if (folders.isEmpty()) 0 else 1
        return if (index < folders.size) index + folderLabel else index + folderLabel + 1
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvSearchResultList(
    groups: TvSearchGroups,
    paging: SearchPaging,
    restoreRowId: Long?,
    rows: TvSearchRows,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    owner: TvPaneFocusOwner,
    listFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val items = groups.ordered
    val anchorRow = remember { FocusRequester() }
    val lastRow = remember { FocusRequester() }
    val pagingHeldFocus = remember { mutableStateOf(false) }
    val handOffToLastRow = remember { mutableStateOf(false) }
    if (paging == SearchPaging.Complete && pagingHeldFocus.value) {
        pagingHeldFocus.value = false
        handOffToLastRow.value = true
    }
    val listState = rememberLazyListState()
    val anchorId by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long }?.key as Long? }
    }
    val restoreRow = remember { FocusRequester() }
    val restoreComposed by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == restoreRowId } }
    }
    LaunchedEffect(listState) {
        val id = restoreRowId ?: return@LaunchedEffect
        val index = groups.lazyIndexOf(id)
        if (index < 0) return@LaunchedEffect
        if (!restoreComposed) listState.scrollToItem(index)
        snapshotFlow { restoreComposed }.first { it }
        owner.enter(listFocus)
        withFrameNanos {}
        if (owner.hasFocus) restoreRow.requestFocus()
    }
    LaunchedEffect(handOffToLastRow.value) {
        if (!handOffToLastRow.value) return@LaunchedEffect
        val last = items.last().id.value
        listState.bringIntoComposition(last, groups.lazyIndexOf(last))
        withFrameNanos {}
        if (owner.owns(listFocus)) lastRow.requestFocus()
        handOffToLastRow.value = false
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .paneSection(owner, listFocus)
            .focusRequester(listFocus)
            .focusRestorer {
                val lastId = items.last().id.value
                val lastComposed = listState.layoutInfo.visibleItemsInfo.any { it.key == lastId }
                when {
                    handOffToLastRow.value && lastComposed -> lastRow
                    restoreComposed -> restoreRow
                    else -> anchorRow
                }
            }
            .focusGroup()
            .testTag(TV_SEARCH_RESULTS_TAG),
    ) {
        val requesters =
            TvSearchRowRequesters({ anchorId }, anchorRow, items.last().id.value, lastRow, restoreRowId, restoreRow)
        val row: @Composable (FilesItem) -> Unit = { item -> TvSearchResultRow(item, rows, requesters) }
        resultGroup(R.string.tv_search_group_folders, "folders", groups.folders, first = true, row = row)
        resultGroup(
            R.string.tv_search_group_files,
            "files",
            groups.files,
            first = groups.folders.isEmpty(),
            row = row,
        )
        if (paging != SearchPaging.Complete) {
            item(key = TV_SEARCH_PAGING_KEY) {
                TvSearchPaging(
                    paging = paging,
                    onNextPage = onNextPage,
                    onRetry = onRetry,
                    buttonModifier = Modifier.onFocusChanged { pagingHeldFocus.value = it.isFocused },
                )
            }
        }
    }
}

private class TvSearchRowRequesters(
    val anchorId: () -> Long?,
    val anchor: FocusRequester,
    val lastId: Long,
    val last: FocusRequester,
    val restoreId: Long?,
    val restore: FocusRequester,
)

@Composable
private fun TvSearchResultRow(item: FilesItem, rows: TvSearchRows, requesters: TvSearchRowRequesters) {
    val id = item.id.value
    TvFilesRow(
        item = item,
        onClick = { rows.onResult(item) },
        onActions = rows.onActions?.let { open -> { open(item) } },
        label = stringResource(item.resultLabel(), item.name),
        place = rows.place(item),
        modifier = Modifier
            .onFocusChanged { if (it.isFocused) rows.onFocused(item) }
            .focusRequesterIf(id == requesters.anchorId(), requesters.anchor)
            .focusRequesterIf(id == requesters.lastId, requesters.last)
            .focusRequesterIf(id == requesters.restoreId, requesters.restore)
            .focusRequesterIf(id == rows.detailId, rows.detailRow),
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
    item(key = "group-$key") {
        TvSearchGroupLabel(stringResource(title), items.size, first)
    }
    items(items, key = { it.id.value }) { row(it) }
}

@Composable
private fun TvSearchGroupLabel(title: String, count: Int, first: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LABEL_INSET, end = LABEL_INSET, top = if (first) 0.dp else LABEL_SPACE_ABOVE)
            .padding(bottom = TV_SEARCH_LABEL_GAP + LABEL_BASELINE_SLACK)
            .height(TV_SEARCH_LABEL_HEIGHT)
            .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(LABEL_COUNT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text(title, style = PutioTypeScale.tvLabel, color = color)
        Text(count.toString(), style = LABEL_COUNT, color = color)
    }
}

@StringRes
private fun FilesItem.resultLabel(): Int =
    when {
        isPlayable -> R.string.tv_files_play_media
        else -> R.string.tv_search_open_result
    }

internal val TV_SEARCH_LABEL_HEIGHT = 16.dp
internal val TV_SEARCH_LABEL_GAP = 4.dp
private val LABEL_INSET = 12.dp
private val LABEL_SPACE_ABOVE = 8.dp
private val LABEL_BASELINE_SLACK = 0.5.dp
private val LABEL_COUNT_GAP = 8.dp
private val LABEL_COUNT = PutioTypeScale.tvMono.copy(fontWeight = FontWeight.Medium)
internal const val TV_SEARCH_PAGING_KEY = "tv-search-paging"
