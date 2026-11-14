package io.putdotio.android.tv.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesViewportPosition
import io.putdotio.android.tv.KeepTopWhenRowsArriveAbove
import io.putdotio.android.tv.TvAutoPaging
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.holdAtPendingEnd
import io.putdotio.android.tv.isNearEnd
import io.putdotio.android.tv.outrunsScroll
import kotlin.math.abs
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
internal fun TvFilesList(
    folderId: Long,
    content: FilesContent.Ready,
    pagingEnabled: Boolean,
    focusMemory: MutableMap<Long, Long>,
    paneHasFocus: State<Boolean>,
    headerHasFocus: State<Boolean>,
    detailOwnsFocus: State<Boolean>,
    downFromLast: FocusRequester?,
    onEntryTarget: (FocusRequester) -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onOpen: (FilesItem) -> Unit,
    onActions: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
    onRowFocused: (FilesItem) -> Unit = {},
    rowsShowFocus: Boolean = true,
) {
    val viewport = content.viewport
    val listState = key(folderId) {
        rememberLazyListState(viewport.firstVisibleItemIndex, viewport.firstVisibleItemScrollOffset)
    }
    val rowsHoldFocus = remember(listState) { mutableStateOf(false) }
    listState.KeepTopWhenRowsArriveAbove(content.items.firstOrNull()?.id?.value) { rowsHoldFocus.value }
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnActions by rememberUpdatedState(onActions)
    val currentOnRowFocused by rememberUpdatedState(onRowFocused)
    val remembered = focusMemory[folderId]
    val resumeOnPaging = remembered == PAGING_FOCUS_MARKER && content.paging is FilesPaging.Failed
    val rowFocus = remember(listState) { FocusRequester() }
    // Follows whichever row holds focus, so re-entering the pane from the rail lands on
    // the live owner rather than the row that was focused at mount.
    val focusedRowId = remember(listState) { mutableStateOf<Long?>(null) }
    val liveRowFocus = remember(listState) { FocusRequester() }
    val listPagingFocus = remember(listState) { FocusRequester() }
    // Set when the paging control gains focus and cleared only when a row does, so the
    // control losing focus by being disposed does not erase the fact that it had it.
    val pagingHeldFocus = remember(listState) { mutableStateOf(resumeOnPaging) }
    // A sort or refresh swaps the rows without remounting the list; the row that held focus
    // may have moved off screen or gone, so the live requester is only trusted while it is
    // attached to a row that is still listed.
    val listedIds = remember(content.items) { content.items.mapTo(HashSet()) { it.id.value } }
    val rowStillFocused = { rowKeepsFocus(headerHasFocus, detailOwnsFocus, pagingHeldFocus) }
    val rowOwnedFocus = Snapshot.withoutReadObservation { focusedRowId.value != null && rowStillFocused() }
    val paging = content.paging
    val entryFocus = remember(listState, paging, listedIds) {
        derivedStateOf {
            paging.listEntryFocus(
                pagingHeld = pagingHeldFocus.value,
                liveRowListed = focusedRowId.value.let { it != null && it in listedIds },
                pagingFocus = listPagingFocus,
                liveRowFocus = liveRowFocus,
                rowFocus = rowFocus,
            )
        }
    }.value
    SideEffect { onEntryTarget(entryFocus) }
    val handOffToLastRow = remember(listState) { mutableStateOf(false) }
    if (content.paging !is FilesPaging.Failed && pagingHeldFocus.value) {
        val lastId = content.items.last().id.value
        focusMemory[folderId] = lastId
        focusedRowId.value = lastId
        handOffToLastRow.value = true
        pagingHeldFocus.value = false
    }
    // On every mount the remembered row for this folder takes focus, or the first row the
    // first time in. The row is scrolled into view first, since a lazy row that is not
    // composed has no focus requester to answer.
    val focusTarget = content.mountFocusTarget(remembered)
    val rowFocusTarget = rememberUpdatedState(focusTarget)
    // After the rows change under a mounted list, keep the row that had focus composed, or the
    // fallback target when that row is gone: the panel's Left and the pane's enter redirect
    // land on it. Focus itself moves only if a row held it.
    LaunchedEffect(content.items) {
        withFrameNanos {}
        val requester = listState.revealFocusedRow(content.items, focusedRowId, focusTarget, liveRowFocus, rowFocus)
        if (rowOwnedFocus) requester?.requestFocusNextFrame(paneHasFocus, rowStillFocused)
    }
    LaunchedEffect(handOffToLastRow.value) {
        if (!handOffToLastRow.value) return@LaunchedEffect
        val detailOwnedAtStart = detailOwnsFocus.value
        val lastId = content.items.last().id.value
        listState.scrollIntoView(lastId, content.items.lastIndex)
        listState.awaitVisible(lastId)
        // The row is always scrolled into view so the rail's enter target is composed; the
        // restorer answers the removed node first, so the request goes out after that frame,
        // and only if the user has not left for the rail during the wait.
        liveRowFocus.requestFocusNextFrame(paneHasFocus) { !detailOwnsFocus.enteredSince(detailOwnedAtStart) }
        handOffToLastRow.value = false
    }
    LaunchedEffect(listState) {
        // A list mounted while the user is on the rail (Loading → Ready after Left) keeps its
        // entry target ready but does not take focus; the enter redirect delivers it later.
        // Read after the layout waits, since the user may leave for the rail or enter the
        // detail during them.
        val detailOwnedAtMount = detailOwnsFocus.value
        val detailEntered = { detailOwnsFocus.enteredSince(detailOwnedAtMount) }
        listState.scrollIntoView(focusTarget, content.items.indexOfFirst { it.id.value == focusTarget })
        if (resumeOnPaging) {
            listState.scrollToItem(content.items.size)
            listState.awaitVisible(TV_FILES_PAGING_KEY)
            listPagingFocus.requestFocusNextFrame(paneHasFocus) { !detailEntered() }
        } else {
            listState.awaitVisible(focusTarget)
            // The shell's pane request lands one frame earlier; this one settles on the row.
            rowFocus.requestFocusNextFrame(paneHasFocus) { !detailEntered() }
        }
        // Started after the programmatic scroll so the settled position it reports is the one
        // on screen. Only the mount position is skipped: it came from the reducer already.
        listState.reportViewport(viewport) { currentOnEvent(FilesBrowserEvent.ViewportChanged(it)) }
    }

    val lastItemId = content.items.last().id
    val lastRowId = lastItemId.takeIf { content.paging !is FilesPaging.Failed }
    val pendingEndId = lastItemId.takeIf { downFromLast == null && content.paging.isPending }
    TvAutoPaging(
        page = (content.paging as? FilesPaging.Available)?.takeIf { pagingEnabled },
        onLoadMore = { currentOnEvent(FilesBrowserEvent.LoadNextPage) },
        nearEnd = { listState.isNearEnd(content.items.lastIndex) },
    )
    RevealOnlyHiddenRows {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxWidth()
                .onFocusChanged { rowsHoldFocus.value = it.hasFocus }
                .onPreviewKeyEvent { it.outrunsScroll(listState, focusedRowId.value, pagingHeldFocus.value) }
                .focusRestorer(entryFocus)
                .focusGroup()
                .testTag(TV_FILES_LIST_TAG),
        ) {
            items(items = content.items, key = { it.id.value }, contentType = { "row" }) { item ->
                val holdsLiveFocus by remember(focusedRowId, item.id.value) {
                    derivedStateOf { focusedRowId.value == item.id.value }
                }
                val takesRowFocus by remember(rowFocusTarget, item.id.value) {
                    derivedStateOf { rowFocusTarget.value == item.id.value }
                }
                TvFilesRow(
                    item = item,
                    onClick = { currentOnOpen(item) },
                    onActions = { currentOnActions(item) },
                    showsFocus = rowsShowFocus,
                    modifier = Modifier
                        .downTo(downFromLast.takeIf { item.id == lastRowId })
                        .holdAtPendingEnd(item.id == pendingEndId)
                        .focusRequesterIf(takesRowFocus, rowFocus)
                        .focusRequesterIf(holdsLiveFocus, liveRowFocus)
                        .onFocusChanged {
                            if (it.isFocused) {
                                focusMemory[folderId] = item.id.value
                                focusedRowId.value = item.id.value
                                pagingHeldFocus.value = false
                                currentOnRowFocused(item)
                            }
                        },
                )
            }
            if (content.paging != FilesPaging.Complete) {
                item(key = TV_FILES_PAGING_KEY, contentType = "paging") {
                    TvFilesPaging(
                        paging = content.paging,
                        enabled = pagingEnabled,
                        onEvent = onEvent,
                        buttonModifier = Modifier
                            .downTo(downFromLast)
                            .focusRequester(listPagingFocus)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    pagingHeldFocus.value = true
                                    focusMemory[folderId] = PAGING_FOCUS_MARKER
                                }
                            },
                    )
                }
            }
        }
    }
}

private fun Modifier.downTo(target: FocusRequester?): Modifier =
    if (target == null) this else focusProperties { down = target }

private val FilesPaging.isPending: Boolean
    get() = this is FilesPaging.Available || this is FilesPaging.Loading

private fun FilesPaging.listEntryFocus(
    pagingHeld: Boolean,
    liveRowListed: Boolean,
    pagingFocus: FocusRequester,
    liveRowFocus: FocusRequester,
    rowFocus: FocusRequester,
): FocusRequester =
    when {
        pagingHeld && this is FilesPaging.Failed -> pagingFocus
        !liveRowListed -> rowFocus
        else -> liveRowFocus
    }

private fun FilesContent.Ready.mountFocusTarget(remembered: Long?): Long =
    when {
        remembered == PAGING_FOCUS_MARKER && paging !is FilesPaging.Failed -> items.last().id.value
        else -> items.firstOrNull { it.id.value == remembered }?.id?.value ?: items.first().id.value
    }

/**
 * Scrolls to the focused row, or to [fallbackId] once that row is no longer listed, and returns
 * the requester attached to it; null when no row has held focus.
 */
private suspend fun LazyListState.revealFocusedRow(
    items: List<FilesItem>,
    focusedRowId: MutableState<Long?>,
    fallbackId: Long,
    liveRowFocus: FocusRequester,
    rowFocus: FocusRequester,
): FocusRequester? {
    val liveId = focusedRowId.value ?: return null
    val index = items.indexOfFirst { it.id.value == liveId }
    val (targetId, requester) = if (index >= 0) liveId to liveRowFocus else fallbackId to rowFocus
    if (index < 0) focusedRowId.value = null
    scrollIntoView(targetId, items.indexOfFirst { it.id.value == targetId })
    awaitVisible(targetId)
    return requester
}

private suspend fun LazyListState.scrollIntoView(key: Long, index: Int) {
    if (index >= 0 && layoutInfo.visibleItemsInfo.none { it.key == key }) scrollToItem(index)
}

private suspend fun LazyListState.awaitVisible(key: Any) {
    snapshotFlow { layoutInfo.visibleItemsInfo.any { it.key == key } }.first { it }
}

private fun rowKeepsFocus(
    headerHasFocus: State<Boolean>,
    detailOwnsFocus: State<Boolean>,
    pagingHeldFocus: State<Boolean>,
): Boolean = !headerHasFocus.value && !detailOwnsFocus.value && !pagingHeldFocus.value

private fun State<Boolean>.enteredSince(heldAtStart: Boolean): Boolean = !heldAtStart && value

private suspend fun FocusRequester.requestFocusNextFrame(paneHasFocus: State<Boolean>, stillWanted: () -> Boolean) {
    withFrameNanos {}
    if (paneHasFocus.value && stillWanted()) requestFocus()
}

private suspend fun LazyListState.reportViewport(
    mounted: FilesViewportPosition,
    onChanged: (FilesViewportPosition) -> Unit,
) {
    var reported = mounted
    snapshotFlow {
        if (isScrollInProgress) {
            null
        } else {
            FilesViewportPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)
        }
    }
        .filterNotNull()
        .collect {
            if (it != reported) {
                reported = it
                onChanged(it)
            }
        }
}

private const val TV_FILES_PAGING_KEY = "tv-files-paging"

/** Item ids are positive, so this marks "the paging control" in the per-folder focus memory. */
private const val PAGING_FOCUS_MARKER = -1L

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RevealOnlyHiddenRows(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides RevealOnlyHidden, content = content)
}

private object RevealOnlyHidden : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f && trailing > containerSize -> 0f
            abs(offset) < abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}
