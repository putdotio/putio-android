package io.putdotio.android.tv.files

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesViewportPosition
import io.putdotio.android.tv.focusRequesterIf
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
    onEntryTarget: (FocusRequester) -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onOpen: (FilesItem) -> Unit,
    onActions: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewport = content.viewport
    val listState = key(folderId) {
        rememberLazyListState(viewport.firstVisibleItemIndex, viewport.firstVisibleItemScrollOffset)
    }
    val currentOnEvent by rememberUpdatedState(onEvent)
    val remembered = focusMemory[folderId]
    val resumeOnPaging = remembered == PAGING_FOCUS_MARKER && content.paging != FilesPaging.Complete
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
    val liveRowListed = focusedRowId.value != null && focusedRowId.value in listedIds
    // A listing change refocuses a row only when a row was what the user was on. A row being
    // disposed by that change also reports losing focus, so ownership is inferred from the
    // other in-pane owners instead: the header controls and the paging latch.
    val rowOwnsFocus = focusedRowId.value != null && !headerHasFocus.value && !pagingHeldFocus.value
    val entryFocus = content.paging.listEntryFocus(
        pagingHeld = pagingHeldFocus.value,
        liveRowListed = liveRowListed,
        pagingFocus = listPagingFocus,
        liveRowFocus = liveRowFocus,
        rowFocus = rowFocus,
    )
    SideEffect { onEntryTarget(entryFocus) }
    // The paging control leaves the list when the last page lands; if it held focus, the
    // last row becomes the remembered row so the restorer's fallback lands there.
    // Decided during composition: once the paging item is gone the list's restorer moves
    // focus to an earlier row and clears the latch before any effect could read it.
    val handOffToLastRow = remember(listState) { mutableStateOf(false) }
    if (content.paging == FilesPaging.Complete && pagingHeldFocus.value) {
        val lastId = content.items.last().id.value
        focusMemory[folderId] = lastId
        focusedRowId.value = lastId
        handOffToLastRow.value = true
        pagingHeldFocus.value = false
    }
    // On every mount the remembered row for this folder takes focus, or the first row the
    // first time in. The row is scrolled into view first, since a lazy row that is not
    // composed has no focus requester to answer.
    // The paging control that held focus is gone if the last page landed while the pane was
    // away; the row that replaced it is the last one.
    val focusTarget = content.mountFocusTarget(remembered)
    // After the rows change under a mounted list, keep focus on the row that had it, or on
    // the fallback target when that row is gone.
    LaunchedEffect(content.items) {
        if (!rowOwnsFocus) return@LaunchedEffect
        listState.revealFocusedRow(content.items, focusedRowId, focusTarget, liveRowFocus, rowFocus)
            ?.requestFocusNextFrame(paneHasFocus)
    }
    LaunchedEffect(handOffToLastRow.value) {
        if (!handOffToLastRow.value) return@LaunchedEffect
        val lastId = content.items.last().id.value
        listState.scrollIntoView(lastId, content.items.lastIndex)
        listState.awaitVisible(lastId)
        // The row is always scrolled into view so the rail's enter target is composed; the
        // restorer answers the removed node first, so the request goes out after that frame,
        // and only if the user has not left for the rail during the wait.
        liveRowFocus.requestFocusNextFrame(paneHasFocus)
        handOffToLastRow.value = false
    }
    LaunchedEffect(listState) {
        // A list mounted while the user is on the rail (Loading → Ready after Left) keeps its
        // entry target ready but does not take focus; the enter redirect delivers it later.
        // Read after the layout waits, since the user may leave for the rail during them.
        listState.scrollIntoView(focusTarget, content.items.indexOfFirst { it.id.value == focusTarget })
        if (resumeOnPaging) {
            listState.scrollToItem(content.items.size)
            listState.awaitVisible(TV_FILES_PAGING_KEY)
            listPagingFocus.requestFocusNextFrame(paneHasFocus)
        } else {
            listState.awaitVisible(focusTarget)
            // The shell's pane request lands one frame earlier; this one settles on the row.
            rowFocus.requestFocusNextFrame(paneHasFocus)
        }
        // Started after the programmatic scroll so the settled position it reports is the one
        // on screen. Only the mount position is skipped: it came from the reducer already.
        listState.reportViewport(viewport) { currentOnEvent(FilesBrowserEvent.ViewportChanged(it)) }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .focusRestorer(entryFocus)
            .focusGroup()
            .testTag(TV_FILES_LIST_TAG),
    ) {
        items(items = content.items, key = { it.id.value }) { item ->
            TvFilesRow(
                item = item,
                onClick = { onOpen(item) },
                onActions = { onActions(item) },
                modifier = Modifier
                    .focusRequesterIf(item.id.value == focusTarget, rowFocus)
                    .focusRequesterIf(item.id.value == focusedRowId.value, liveRowFocus)
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusMemory[folderId] = item.id.value
                            focusedRowId.value = item.id.value
                            pagingHeldFocus.value = false
                        }
                    },
            )
        }
        if (content.paging != FilesPaging.Complete) {
            item(key = TV_FILES_PAGING_KEY) {
                TvFilesPaging(
                    paging = content.paging,
                    enabled = pagingEnabled,
                    onEvent = onEvent,
                    buttonModifier = Modifier
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

private fun FilesPaging.listEntryFocus(
    pagingHeld: Boolean,
    liveRowListed: Boolean,
    pagingFocus: FocusRequester,
    liveRowFocus: FocusRequester,
    rowFocus: FocusRequester,
): FocusRequester =
    when {
        pagingHeld && this != FilesPaging.Complete -> pagingFocus
        !liveRowListed -> rowFocus
        else -> liveRowFocus
    }

private fun FilesContent.Ready.mountFocusTarget(remembered: Long?): Long =
    when {
        remembered == PAGING_FOCUS_MARKER && paging == FilesPaging.Complete -> items.last().id.value
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

private suspend fun FocusRequester.requestFocusNextFrame(paneHasFocus: State<Boolean>) {
    withFrameNanos {}
    if (paneHasFocus.value) requestFocus()
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
