package io.putdotio.android.tv.trash

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.tv.TV_LIST_ROW_META
import io.putdotio.android.tv.TV_LIST_ROW_META_MONO
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvListRow
import io.putdotio.android.tv.TvListRowMetaParts
import io.putdotio.android.tv.bringIntoComposition
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.paneSection
import kotlinx.coroutines.flow.first

internal const val TV_TRASH_LIST_TAG = "tv-trash-list"
internal const val TV_TRASH_ROW_TAG = "tv-trash-row"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvTrashList(
    content: TrashContent.Loaded,
    restoreRowId: Long?,
    onChoose: (TrashItem) -> Unit,
    onRowFocusChanged: (TrashItem, Boolean) -> Unit,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    focus: TvTrashListFocus,
    modifier: Modifier = Modifier,
) {
    val items = content.items
    val rows = remember { TrashRowFocus() }
    val pagingFocus = remember { FocusRequester() }
    val pagingHeldFocus = remember { mutableStateOf(false) }
    val handOffToLastRow = remember { mutableStateOf(false) }
    val pagingShown = content.showsPaging
    if (!pagingShown && pagingHeldFocus.value) {
        pagingHeldFocus.value = false
        handOffToLastRow.value = true
    }
    val listState = rememberLazyListState()
    val anchorIndex by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex } }
    val restoreIndex = remember(items) { items.indexOfFirst { it.id.value == restoreRowId } }
    LaunchedEffect(listState) {
        if (!listState.awaitMountTarget(items, restoreIndex, anchorIndex, pagingShown)) return@LaunchedEffect
        withFrameNanos {}
        if (!mayPlaceFocus(focus.paneHasFocus, focus.dialogShowing)) return@LaunchedEffect
        if (restoreIndex >= 0) rows.restore.requestFocus() else focus.list.requestFocus()
    }
    LaunchedEffect(handOffToLastRow.value) {
        if (!handOffToLastRow.value) return@LaunchedEffect
        val lastId = items.lastOrNull()?.id?.value ?: return@LaunchedEffect
        listState.bringIntoComposition(lastId, items.lastIndex)
        withFrameNanos {}
        if (focus.owner.owns(focus.list)) rows.last.requestFocus()
        handOffToLastRow.value = false
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .paneSection(focus.owner, focus.list)
            .focusRequester(focus.list)
            .focusRestorer {
                val lastId = items.lastOrNull()?.id?.value ?: return@focusRestorer pagingFocus
                listState.restoreTarget(rows, lastId, items.getOrNull(restoreIndex)?.id?.value, handOffToLastRow.value)
            }
            .focusGroup()
            .testTag(TV_TRASH_LIST_TAG),
    ) {
        itemsIndexed(items, key = { _, item -> item.id.value }) { index, item ->
            TvTrashRow(
                item = item,
                onClick = { onChoose(item) },
                showsFocus = !focus.dialogShowing.value,
                modifier = Modifier
                    .focusRequesterIf(index == anchorIndex, rows.anchor)
                    .focusRequesterIf(index == items.lastIndex, rows.last)
                    .focusRequesterIf(index == restoreIndex, rows.restore)
                    .onFocusChanged { onRowFocusChanged(item, it.isFocused) },
            )
        }
        if (pagingShown) {
            item(key = TV_TRASH_PAGING_KEY) {
                TvTrashPaging(
                    content = content,
                    onNextPage = onNextPage,
                    onRetry = onRetry,
                    buttonModifier = Modifier
                        .focusRequester(pagingFocus)
                        .onFocusChanged { pagingHeldFocus.value = it.isFocused },
                )
            }
        }
    }
}

internal fun mayPlaceFocus(paneHasFocus: State<Boolean>, dialogShowing: State<Boolean>): Boolean =
    paneHasFocus.value && !dialogShowing.value

private class TrashRowFocus {
    val anchor = FocusRequester()
    val last = FocusRequester()
    val restore = FocusRequester()
}

private fun LazyListState.restoreTarget(
    rows: TrashRowFocus,
    lastId: Long,
    restoreId: Long?,
    handOffToLastRow: Boolean,
): FocusRequester = when {
    handOffToLastRow && composes(lastId) -> rows.last
    composes(restoreId) -> rows.restore
    else -> rows.anchor
}

private fun LazyListState.composes(key: Any?): Boolean =
    key != null && layoutInfo.visibleItemsInfo.any { it.key == key }

private suspend fun LazyListState.awaitMountTarget(
    items: List<TrashItem>,
    restoreIndex: Int,
    anchorIndex: Int,
    pagingShown: Boolean,
): Boolean {
    val restoreKey = items.getOrNull(restoreIndex)?.id?.value
    if (restoreKey != null) bringIntoComposition(restoreKey, restoreIndex)
    val targetKey = restoreKey ?: items.getOrNull(anchorIndex)?.id?.value
        ?: TV_TRASH_PAGING_KEY.takeIf { pagingShown } ?: return false
    snapshotFlow { composes(targetKey) }.first { it }
    return true
}

private val TrashContent.Loaded.showsPaging: Boolean
    get() = nextCursor != null || isLoadingMore || pageFailure != null

@Composable
private fun TvTrashRow(
    item: TrashItem,
    onClick: () -> Unit,
    showsFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    TvListRow(
        glyph = fileTypeIconRes(item.type),
        title = item.name,
        onClick = onClick,
        showsFocus = showsFocus,
        modifier = modifier.testTag(TV_TRASH_ROW_TAG),
    ) { secondary -> TvTrashRowMeta(item, secondary) }
}

@Composable
private fun TvTrashRowMeta(item: TrashItem, color: Color) {
    val daysLeft = rememberTrashDaysLeft(item)
    val parts = buildList<@Composable () -> Unit> {
        add { Text(formatFileSize(item.sizeBytes), style = TV_LIST_ROW_META_MONO, color = color, maxLines = 1) }
        daysLeft?.let { add { Text(daysLeftLabel(it), style = TV_LIST_ROW_META, color = color, maxLines = 1) } }
    }
    TvListRowMetaParts(parts, color)
}

@Composable
private fun daysLeftLabel(days: Long): AnnotatedString =
    if (days == 0L) {
        AnnotatedString(stringResource(R.string.tv_trash_last_day))
    } else {
        val count = days.toInt()
        trashCount(R.plurals.tv_trash_days_left, count)
    }

@Composable
private fun TvTrashPaging(
    content: TrashContent.Loaded,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
) {
    val failure = content.pageFailure
    val label = when {
        content.isLoadingMore -> R.string.tv_trash_loading_more
        failure != null -> R.string.tv_files_retry
        else -> R.string.tv_trash_load_more
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (failure != null) {
            Text(stringResource(R.string.tv_trash_paging_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TvButton(
            onClick = {
                when {
                    content.isLoadingMore -> Unit
                    failure != null -> onRetry()
                    else -> onNextPage()
                }
            },
            modifier = buttonModifier,
        ) {
            Text(stringResource(label))
        }
    }
}

private const val TV_TRASH_PAGING_KEY = "tv-trash-paging"
