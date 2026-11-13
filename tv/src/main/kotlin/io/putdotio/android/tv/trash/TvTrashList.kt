package io.putdotio.android.tv.trash

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.tv.KeepTopWhenRowsArriveAbove
import io.putdotio.android.tv.TV_LIST_ROW_META
import io.putdotio.android.tv.TV_LIST_ROW_META_MONO
import io.putdotio.android.tv.TvAutoPaging
import io.putdotio.android.tv.TvListPaging
import io.putdotio.android.tv.TvListRow
import io.putdotio.android.tv.TvListRowMetaParts
import io.putdotio.android.tv.bringIntoComposition
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.holdAtPendingEnd
import io.putdotio.android.tv.isNearEnd
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
    if (content.pageFailure == null && pagingHeldFocus.value) {
        pagingHeldFocus.value = false
        handOffToLastRow.value = true
    }
    val listState = rememberLazyListState()
    val rowsHoldFocus = remember { mutableStateOf(false) }
    listState.KeepTopWhenRowsArriveAbove(items.firstOrNull()?.id?.value) { rowsHoldFocus.value }
    val anchorIndex by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex } }
    val restoreIndex = remember(items) { items.indexOfFirst { it.id.value == restoreRowId } }
    TvAutoPaging(
        page = content.takeIf { it.mayLoadNextPage && !focus.dialogShowing.value },
        onLoadMore = onNextPage,
        nearEnd = { listState.isNearEnd(items.lastIndex) },
    )
    LaunchedEffect(listState) {
        if (!listState.awaitMountTarget(items, restoreIndex, anchorIndex, pagingShown)) return@LaunchedEffect
        withFrameNanos {}
        if (!mayPlaceFocus(focus.paneHasFocus, focus.dialogShowing)) return@LaunchedEffect
        if (restoreIndex >= 0) rows.restore.requestFocus() else focus.list.requestFocus()
    }
    val heldRowId = remember { mutableStateOf<Long?>(null) }
    val nextRowId = remember { mutableStateOf<Long?>(null) }
    val nextIndex = items.indexOfFirst { it.id.value == nextRowId.value }
    TvTrashHeldRowLeaves(items, heldRowId, nextRowId, listState, focus, rows.next)
    TvTrashLastRowHandOff(items, handOffToLastRow, listState, focus, rows.last)
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .paneSection(focus.owner, focus.list)
            .focusRequester(focus.list)
            .onFocusChanged { rowsHoldFocus.value = it.hasFocus }
            .focusRestorer {
                val lastId = items.lastOrNull()?.id?.value
                    ?: return@focusRestorer if (content.pageFailure != null) pagingFocus else FocusRequester.Default
                listState.restoreTarget(
                    rows, lastId, nextRowId.value, items.getOrNull(restoreIndex)?.id?.value, handOffToLastRow.value,
                )
            }
            .focusGroup()
            .testTag(TV_TRASH_LIST_TAG),
    ) {
        itemsIndexed(items, key = { _, item -> item.id.value }) { index, item ->
            TvTrashRow(
                item = item,
                onClick = { onChoose(item) },
                showsFocus = !focus.dialogShowing.value,
                modifier = with(rows) {
                    Modifier
                        .requesters(index, items.lastIndex, anchorIndex, restoreIndex, content.pagePending)
                        .focusRequesterIf(index == nextIndex, next)
                        .onFocusChanged {
                            if (it.isFocused) {
                                heldRowId.value = item.id.value
                                nextRowId.value = null
                            }
                            onRowFocusChanged(item, it.isFocused)
                        }
                },
            )
        }
        if (pagingShown) {
            item(key = TV_TRASH_PAGING_KEY) {
                TvTrashPaging(
                    content = content,
                    onRetry = onRetry,
                    buttonModifier = Modifier
                        .focusRequester(pagingFocus)
                        .onFocusChanged { pagingHeldFocus.value = it.isFocused },
                )
            }
        }
    }
}

@Composable
private fun TvTrashHeldRowLeaves(
    items: List<TrashItem>,
    heldRowId: MutableState<Long?>,
    nextRowId: MutableState<Long?>,
    listState: LazyListState,
    focus: TvTrashListFocus,
    nextRow: FocusRequester,
) {
    val shown = remember { mutableStateOf(items) }
    LaunchedEffect(items) {
        val before = shown.value
        shown.value = items
        val held = heldRowId.value ?: return@LaunchedEffect
        if (items.isEmpty() || items.any { it.id.value == held }) return@LaunchedEffect
        heldRowId.value = null
        val target = before.survivorAfter(held, items) ?: items.first()
        val index = items.indexOf(target)
        nextRowId.value = target.id.value
        if (!focus.owner.owns(focus.list) || !mayPlaceFocus(focus.paneHasFocus, focus.dialogShowing)) {
            return@LaunchedEffect
        }
        listState.bringIntoComposition(target.id.value, index)
        withFrameNanos {}
        if (mayPlaceFocus(focus.paneHasFocus, focus.dialogShowing) && focus.owner.owns(focus.list)) {
            nextRow.requestFocus()
        }
    }
}

private fun List<TrashItem>.survivorAfter(goneId: Long, now: List<TrashItem>): TrashItem? {
    val kept = now.associateBy { it.id }
    val at = indexOfFirst { it.id.value == goneId }
    if (at < 0) return null
    return drop(at + 1).firstNotNullOfOrNull { kept[it.id] }
        ?: take(at).asReversed().firstNotNullOfOrNull { kept[it.id] }
}

@Composable
private fun TvTrashLastRowHandOff(
    items: List<TrashItem>,
    handOffToLastRow: MutableState<Boolean>,
    listState: LazyListState,
    focus: TvTrashListFocus,
    lastRow: FocusRequester,
) {
    LaunchedEffect(handOffToLastRow.value) {
        if (!handOffToLastRow.value) return@LaunchedEffect
        val lastId = items.lastOrNull()?.id?.value ?: return@LaunchedEffect
        listState.bringIntoComposition(lastId, items.lastIndex)
        withFrameNanos {}
        if (focus.owner.owns(focus.list)) lastRow.requestFocus()
        handOffToLastRow.value = false
    }
}

internal fun mayPlaceFocus(paneHasFocus: State<Boolean>, dialogShowing: State<Boolean>): Boolean =
    paneHasFocus.value && !dialogShowing.value

private class TrashRowFocus {
    val anchor = FocusRequester()
    val last = FocusRequester()
    val restore = FocusRequester()
    val next = FocusRequester()

    fun Modifier.requesters(
        index: Int,
        lastIndex: Int,
        anchorIndex: Int,
        restoreIndex: Int,
        pagePending: Boolean,
    ): Modifier =
        this
            .focusRequesterIf(index == anchorIndex, anchor)
            .focusRequesterIf(index == lastIndex, last)
            .holdAtPendingEnd(pagePending && index == lastIndex)
            .focusRequesterIf(index == restoreIndex, restore)
}

private fun LazyListState.restoreTarget(
    rows: TrashRowFocus,
    lastId: Long,
    nextId: Long?,
    restoreId: Long?,
    handOffToLastRow: Boolean,
): FocusRequester = when {
    handOffToLastRow && composes(lastId) -> rows.last
    composes(nextId) -> rows.next
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

private val TrashContent.Loaded.pagePending: Boolean
    get() = nextCursor != null && pageFailure == null

private val TrashContent.Loaded.mayLoadNextPage: Boolean
    get() = pagePending && !isBusy

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
        add {
            Text(
                formatFileSize(item.sizeBytes),
                style = TV_LIST_ROW_META_MONO,
                color = color,
                maxLines = 1,
            )
        }
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
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
) {
    TvListPaging(
        failed = content.pageFailure != null && !content.isLoadingMore,
        loadingMore = stringResource(R.string.tv_trash_loading_more),
        error = stringResource(R.string.tv_trash_paging_error),
        retry = stringResource(R.string.tv_files_retry),
        onRetry = onRetry,
        modifier = modifier,
        buttonModifier = buttonModifier,
    )
}

private const val TV_TRASH_PAGING_KEY = "tv-trash-paging"
