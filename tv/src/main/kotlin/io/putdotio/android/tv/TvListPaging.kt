package io.putdotio.android.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.first

@Composable
internal fun TvListPaging(
    failed: Boolean,
    loadingMore: String,
    error: String,
    retry: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
    errorStyle: TextStyle = LocalTextStyle.current,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = PAGING_ROW_PADDING),
        horizontalArrangement = Arrangement.spacedBy(PAGING_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (failed) {
            Text(error, style = errorStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TvButton(onClick = onRetry, modifier = buttonModifier) { Text(retry) }
        } else {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(SPINNER_SIZE)
                    .clearAndSetSemantics { contentDescription = loadingMore },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = SPINNER_STROKE,
            )
        }
    }
}

@Composable
internal fun TvAutoPaging(page: Any?, onLoadMore: () -> Unit, nearEnd: () -> Boolean = { true }) {
    val load by rememberUpdatedState(onLoadMore)
    val near by rememberUpdatedState(nearEnd)
    LaunchedEffect(page) {
        if (page == null) return@LaunchedEffect
        snapshotFlow { near() }.first { it }
        load()
    }
}

@Composable
internal fun LazyListState.KeepTopWhenRowsArriveAbove(firstKey: Any?, rowsHoldFocus: () -> Boolean) {
    val seen = remember(this) { SeenFirstKey(firstKey) }
    if (seen.value == firstKey) return
    seen.value = firstKey
    if (!Snapshot.withoutReadObservation { canScrollBackward || rowsHoldFocus() }) requestScrollToItem(0)
}

private class SeenFirstKey(var value: Any?)

internal fun LazyListState.isNearEnd(lastContentIndex: Int): Boolean {
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return lastVisible >= lastContentIndex - TV_PAGING_PREFETCH_ITEMS
}

internal fun KeyEvent.outrunsScroll(list: LazyListState, focusedRow: Long?, pagingHeld: Boolean): Boolean {
    val heldUpOrDown = type == KeyEventType.KeyDown && nativeKeyEvent.repeatCount > 0 &&
        (key == Key.DirectionDown || key == Key.DirectionUp)
    if (focusedRow == null || pagingHeld || !heldUpOrDown) return false
    val layout = list.layoutInfo
    val start = layout.viewportStartOffset
    val end = layout.viewportEndOffset
    val row = layout.visibleItemsInfo.firstOrNull { it.key == focusedRow }
    return row == null || row.size < end - start && (row.offset < start || row.offset + row.size > end)
}

internal fun Modifier.holdAtPendingEnd(holds: Boolean): Modifier =
    if (holds) focusProperties { down = FocusRequester.Cancel } else this

private val PAGING_ROW_PADDING = 8.dp
private val PAGING_GAP = 16.dp
private val SPINNER_SIZE = 20.dp
private val SPINNER_STROKE = 2.dp
private const val TV_PAGING_PREFETCH_ITEMS = 5
