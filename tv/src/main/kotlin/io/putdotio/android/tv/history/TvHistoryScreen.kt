package io.putdotio.android.tv.history

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryClearing
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryState
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvPaneFocusOwner
import io.putdotio.android.tv.TvPickedRow
import io.putdotio.android.tv.TvStatusScreen
import io.putdotio.android.tv.bringIntoComposition
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.tv.paneSection
import io.putdotio.android.tv.refocusAfterDialog
import io.putdotio.android.tv.files.tvMessageText
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import io.putdotio.android.FilesFailure

internal const val TV_HISTORY_LIST_TAG = "tv-history-list"
internal const val TV_HISTORY_ROW_TAG = "tv-history-row"

/**
 * History per the oracle (08): the title with a Clear action on the right, then the
 * account's events grouped under relative-date headers in the standard rows. Center on
 * an event that names a file opens it in Files. Focus enters on the first row; Up from
 * it reaches Clear, Left from anything returns to the drawer.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvHistoryScreen(
    state: HistoryState,
    onEvent: (HistoryEvent) -> Boolean,
    modifier: Modifier = Modifier,
    /** A failure that is not the listing itself: a row whose file could not be opened. */
    notice: FilesFailure? = null,
    /** Changes with the signed-in session so one account's list position never greets the next. */
    sessionKey: Any? = null,
    clock: Clock = Clock.systemDefaultZone(),
    /** The event the viewer last opened; it takes focus again when the pane comes back. */
    pickedRow: TvPickedRow = remember(sessionKey) { TvPickedRow() },
) = key(sessionKey) {
    val restoreRowId = remember { pickedRow.take() }
    val content = state.content
    // Clear stays composed and enabled in every state, like Refresh in Files: a disabled
    // TV button drops focus, and it is the one node that outlives every content change.
    // The reducer ignores the request outside a loaded, idle list.
    val clearFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    // Whether D-pad focus belongs to this pane. Only a directional exit clears it: a node
    // being disposed also drops focus, but the pane still owns it and must re-place it.
    val paneHasFocus = remember { mutableStateOf(true) }
    // Where the shell's entry request and the window's initial focus land: the rows while
    // there are any, Try again on a failure, Clear otherwise. Sections move it as they take
    // focus; the effects below set it as content changes, before any focus can arrive.
    val entryTarget = remember { mutableStateOf(clearFocus) }
    // The home section follows the content, read when a section leaves: by then the
    // content is already the one replacing it, and its own nodes are attached.
    val currentContent = rememberUpdatedState(content)
    val owner = remember {
        TvPaneFocusOwner(entryTarget, paneHasFocus) {
            if (currentContent.value is HistoryContent.Ready) listFocus else clearFocus
        }
    }
    // Loading, empty, and disabled lists have no focusable content, so Clear takes focus
    // when they replace one that had some; a failed list focuses its own Try again.
    val headerOwnsFocus = content.hasNothingToFocus
    LaunchedEffect(headerOwnsFocus) {
        if (headerOwnsFocus && paneHasFocus.value) clearFocus.requestFocus()
    }
    // The dialog window takes focus while it is up; when it closes the pane is focused
    // again but nothing in it is, so the section that had focus takes it back.
    val dialogOpen = state.clearing != HistoryClearing.Idle
    val dialogWasOpen = remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) { refocusAfterDialog(dialogOpen, dialogWasOpen, paneHasFocus, entryTarget) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .onFocusChanged { if (it.hasFocus) paneHasFocus.value = true }
            .focusProperties {
                enter = { entryTarget.value }
                exit = {
                    paneHasFocus.value = false
                    FocusRequester.Default
                }
            }
            .focusGroup(),
    ) {
        TvHistoryHeader(
            onClear = { onEvent(HistoryEvent.RequestClear) },
            modifier = Modifier.paneSection(owner, clearFocus).focusRequester(clearFocus),
        )
        if (notice != null) {
            TvHistoryNotice(notice)
        }
        when (content) {
            HistoryContent.Disabled ->
                TvStatusScreen(
                    title = stringResource(R.string.tv_history_disabled_title),
                    message = stringResource(
                        R.string.tv_history_disabled_message,
                        stringResource(R.string.tv_account_history_enabled),
                        stringResource(R.string.tv_destination_account),
                    ),
                    modifier = Modifier.weight(1f),
                )
            is HistoryContent.Loading ->
                TvStatusScreen(stringResource(R.string.tv_history_loading), modifier = Modifier.weight(1f))
            HistoryContent.Empty ->
                TvStatusScreen(stringResource(R.string.tv_history_empty), modifier = Modifier.weight(1f))
            is HistoryContent.Failed -> {
                DisposableEffect(Unit) {
                    entryTarget.value = retryFocus
                    onDispose {}
                }
                TvStatusScreen(
                    title = stringResource(R.string.tv_history_error_title),
                    message = content.failure.tvMessageText(),
                    action = stringResource(R.string.tv_files_retry),
                    onAction = { onEvent(HistoryEvent.Retry) },
                    modifier = Modifier.paneSection(owner, retryFocus).weight(1f),
                    actionFocus = retryFocus,
                    claimFocus = paneHasFocus.value,
                )
            }
            is HistoryContent.Ready -> {
                // Runs after the section that left has handed the target back, and before
                // the list's own effects: rows that mount are where entry lands, as in Files.
                DisposableEffect(Unit) {
                    entryTarget.value = listFocus
                    onDispose {}
                }
                // Read once per listing, not per recomposition: a moving "now" would regroup
                // the rows and relabel every one of them on each focus change.
                val now = remember(content.items) { clock.instant() }
                TvHistoryList(
                    content = content,
                    restoreRowId = restoreRowId,
                    onOpen = { item, fileId ->
                        pickedRow.pick(item.id.value)
                        onEvent(HistoryEvent.OpenFile(fileId))
                    },
                    onNextPage = { onEvent(HistoryEvent.LoadNextPage) },
                    onRetry = { onEvent(HistoryEvent.Retry) },
                    owner = owner,
                    listFocus = listFocus,
                    paneHasFocus = paneHasFocus,
                    now = now,
                    zone = clock.zone,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    TvHistoryClearDialog(state.clearing, onEvent)
}

private val HistoryContent.hasNothingToFocus: Boolean
    get() = this is HistoryContent.Loading || this == HistoryContent.Empty || this == HistoryContent.Disabled

@Composable
private fun TvHistoryHeader(
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.tv_history_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        TvButton(onClick = onClear, modifier = modifier) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_backspace),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tv_history_clear))
        }
    }
}

@Composable
private fun TvHistoryNotice(notice: FilesFailure) {
    Text(
        text = if (notice == FilesFailure.NavigationBlocked) {
            stringResource(R.string.tv_error_navigation_blocked)
        } else {
            stringResource(R.string.tv_history_open_error, notice.tvMessageText())
        },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** One lazy-list entry: a group header or an event row. */
private sealed interface TvHistoryEntry {
    val key: Any

    data class Header(val bucket: TvHistoryBucket) : TvHistoryEntry {
        override val key get() = "header-${bucket.name}"
    }

    data class Event(val item: HistoryItem) : TvHistoryEntry {
        override val key get() = item.id.value
    }
}

private fun List<HistoryItem>.toEntries(now: Instant, zone: ZoneId): List<TvHistoryEntry> {
    val entries = ArrayList<TvHistoryEntry>(size + TvHistoryBucket.entries.size)
    var current: TvHistoryBucket? = null
    for (item in this) {
        val bucket = item.bucket(now, zone)
        if (bucket != current) {
            current = bucket
            entries += TvHistoryEntry.Header(bucket)
        }
        entries += TvHistoryEntry.Event(item)
    }
    return entries
}

/** The first event row at or below [top], or -1; headers are not focusable. */
private fun List<TvHistoryEntry>.firstRowFrom(top: Int): Int =
    indices.firstOrNull { it >= top && this[it] is TvHistoryEntry.Event } ?: -1

private fun List<TvHistoryEntry>.indexOfRow(id: Long?): Int =
    indexOfFirst { it is TvHistoryEntry.Event && it.item.id.value == id }

/**
 * Waits until the row the list mounts on is composed: the restored row, scrolled to when it is
 * not, else the anchor. False when there is neither.
 */
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
private fun TvHistoryList(
    content: HistoryContent.Ready,
    restoreRowId: Long?,
    onOpen: (HistoryItem, HistoryFileId) -> Unit,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    owner: TvPaneFocusOwner,
    listFocus: FocusRequester,
    paneHasFocus: State<Boolean>,
    now: Instant,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val entries = remember(content.items, now, zone) { content.items.toEntries(now, zone) }
    // The restorer's fallback must name a composed row: the first visible one, since a lazy
    // list composes neither a first row scrolled away nor a last row not yet reached.
    // Headers are not focusable, so the anchor is the first row at or below the top.
    val anchorRow = remember { FocusRequester() }
    val lastRow = remember { FocusRequester() }
    // The paging control leaves the list when the last page lands; if it still held focus,
    // the last row takes over once it is on screen. Decided during composition: the
    // restorer answers the removed paging node in the same frame with the anchor row, and
    // the effect moves on to the last row from there.
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
    // On mount the first row takes focus, also from Clear, which held it while the list
    // loaded; not when the user has left for the drawer, where the enter redirect delivers
    // it later. The shell's own pane request lands one frame earlier on first entry; this
    // one settles on the row. The anchor is read once, at mount: this list is the one that
    // mounted, whatever paging appends to it later.
    // Coming back from what a row opened, that row takes focus instead.
    val restoreRow = remember { FocusRequester() }
    val restoreIndex = remember(entries) { entries.indexOfRow(restoreRowId) }
    LaunchedEffect(listState) {
        if (!listState.awaitMountRow(entries, restoreIndex, anchorIndex)) return@LaunchedEffect
        withFrameNanos {}
        if (paneHasFocus.value) (if (restoreIndex >= 0) restoreRow else listFocus).requestFocus()
    }
    LaunchedEffect(handOffToLastRow.value) {
        if (!handOffToLastRow.value) return@LaunchedEffect
        listState.bringIntoComposition(entries.last().key, lastIndex)
        withFrameNanos {}
        // The user may have left for the drawer or the header during the wait.
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
                val visible = listState.layoutInfo.visibleItemsInfo
                val lastComposed = visible.any { it.key == entries.last().key }
                val restoreComposed = restoreIndex >= 0 && visible.any { it.key == entries[restoreIndex].key }
                when {
                    handOffToLastRow.value && lastComposed -> lastRow
                    restoreComposed -> restoreRow
                    else -> anchorRow
                }
            }
            .focusGroup()
            .testTag(TV_HISTORY_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
            TvHistoryListEntry(
                entry = entry,
                first = index == 0,
                now = now,
                onOpen = onOpen,
                modifier = Modifier
                    .focusRequesterIf(index == anchorIndex, anchorRow)
                    .focusRequesterIf(index == lastIndex, lastRow)
                    .focusRequesterIf(index == restoreIndex, restoreRow),
            )
        }
        if (content.paging != HistoryPaging.Complete) {
            item(key = TV_HISTORY_PAGING_KEY) {
                TvHistoryPaging(
                    paging = content.paging,
                    onNextPage = onNextPage,
                    onRetry = onRetry,
                    buttonModifier = Modifier.onFocusChanged { pagingHeldFocus.value = it.isFocused },
                )
            }
        }
    }
}

@Composable
private fun TvHistoryListEntry(
    entry: TvHistoryEntry,
    first: Boolean,
    now: Instant,
    onOpen: (HistoryItem, HistoryFileId) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (entry) {
        is TvHistoryEntry.Header ->
            Text(
                text = stringResource(entry.bucket.label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.padding(top = if (first) 0.dp else 16.dp, bottom = 8.dp),
            )
        is TvHistoryEntry.Event ->
            TvHistoryRow(
                item = entry.item,
                now = now,
                onOpen = { onOpen(entry.item, it) },
                modifier = modifier,
            )
    }
}

@Composable
private fun TvHistoryRow(
    item: HistoryItem,
    now: Instant,
    onOpen: (HistoryFileId) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The session keeps only shared files and completed transfers (isShownOnTv).
    val title = when (val kind = item.kind) {
        is HistoryEventKind.Transfer -> kind.name ?: stringResource(R.string.tv_history_transfer)
        is HistoryEventKind.File -> kind.name
        is HistoryEventKind.Notice -> kind.subject
        is HistoryEventKind.Other -> null
    } ?: stringResource(R.string.tv_history_file)
    val fileId = item.kind.navigableFileId()
    // An event without a file is still a row the D-pad can rest on; Center does nothing there.
    val label = if (fileId == null) title else stringResource(R.string.tv_history_open_file, title)
    val relative = remember(item, now) { item.relativeTime(now).toString() }
    ListItem(
        selected = false,
        onClick = { if (fileId != null) onOpen(fileId) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                stringResource(R.string.tv_history_metadata, relative, stringResource(item.kind.tvLabel())),
                maxLines = 1,
            )
        },
        leadingContent = {
            Icon(
                painter = painterResource(item.kind.tvIcon()),
                contentDescription = null,
                tint = PutioDesignTokens.yellowSolid,
                modifier = Modifier.size(ListItemDefaults.IconSize),
            )
        },
        scale = ListItemDefaults.scale(focusedScale = FULL_WIDTH_FOCUSED_SCALE),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .testTag(TV_HISTORY_ROW_TAG),
    )
}

@Composable
private fun TvHistoryPaging(
    paging: HistoryPaging,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
) {
    // One button across every paging phase, so the node that holds focus survives the
    // transition from Load more to loading to a retry.
    val label = when (paging) {
        is HistoryPaging.Available -> R.string.tv_history_load_more
        is HistoryPaging.Loading -> R.string.tv_history_loading_more
        is HistoryPaging.Failed -> R.string.tv_files_retry
        HistoryPaging.Complete -> null
    } ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paging is HistoryPaging.Failed) {
            Text(stringResource(R.string.tv_history_paging_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TvButton(
            onClick = {
                when (paging) {
                    is HistoryPaging.Available -> onNextPage()
                    is HistoryPaging.Failed -> onRetry()
                    is HistoryPaging.Loading, HistoryPaging.Complete -> Unit
                }
            },
            modifier = buttonModifier,
        ) {
            Text(stringResource(label))
        }
    }
}

/** Full-width rows scale less than compact surfaces so they stay inside the safe area. */
private const val FULL_WIDTH_FOCUSED_SCALE = 1.02f
private const val TV_HISTORY_PAGING_KEY = "tv-history-paging"
