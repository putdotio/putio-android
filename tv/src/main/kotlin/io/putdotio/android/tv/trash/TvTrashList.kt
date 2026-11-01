package io.putdotio.android.tv.trash

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.tv.TV_LIST_ROW_HEIGHT
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvFocus
import io.putdotio.android.tv.asMonoRun
import io.putdotio.android.tv.bringIntoComposition
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.paneSection
import io.putdotio.android.tv.tvAccent
import io.putdotio.android.tv.withFullLineBox
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

/** The pane places focus itself only while it holds focus and no dialog has taken it. */
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
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val secondary = if (focused) TvFocus.secondaryContent else colors.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(TV_LIST_ROW_HEIGHT)
            .testTag(TV_TRASH_ROW_TAG),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PutioRadius.medium)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = colors.onSurface,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = TvFocus.content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactions,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ROW_PADDING),
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(fileTypeIconRes(item.type)),
                contentDescription = null,
                tint = tvAccent(focused),
                modifier = Modifier.size(ROW_GLYPH),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = ROW_TITLE, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TvTrashRowMeta(item, secondary, Modifier.padding(top = 1.dp))
            }
        }
    }
}

@Composable
private fun TvTrashRowMeta(item: TrashItem, color: Color, modifier: Modifier = Modifier) {
    val daysLeft = rememberTrashDaysLeft(item)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ROW_META_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatFileSize(item.sizeBytes), style = ROW_META_MONO, color = color, maxLines = 1)
        if (daysLeft != null) {
            Text("·", style = ROW_META, color = color, modifier = Modifier.clearAndSetSemantics {})
            Text(daysLeftLabel(daysLeft), style = ROW_META, color = color, maxLines = 1)
        }
    }
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
private val ROW_PADDING = 12.dp
private val ROW_GAP = 16.dp
private val ROW_GLYPH = 20.dp
private val ROW_META_GAP = 5.dp
private val ROW_TITLE = PutioTypeScale.tvBody.copy(lineHeight = 16.sp).withFullLineBox()
private val ROW_META = PutioTypeScale.tvMeta.copy(lineHeight = 14.sp).withFullLineBox()
private val ROW_META_MONO = ROW_META.asMonoRun()
