package io.putdotio.android.tv.files

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesViewportPosition
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvStatusScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import kotlin.math.roundToInt

internal const val TV_FILES_LIST_TAG = "tv-files-list"
internal const val TV_FILES_ROW_TAG = "tv-files-row"

/**
 * Files per the oracle (04-06b): a title row with Refresh and Sort, then
 * full-width rows that open folders, play media, or explain an unsupported
 * type. Focus starts on the first row and the header is reachable with Up.
 * Back pops the folder stack; the shell owns Back when the stack is at root.
 */
@Composable
internal fun TvFilesScreen(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onPlayMedia: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state.current
    var unsupported by rememberSaveable(current.folder.id.value) { mutableStateOf<Long?>(null) }
    val unsupportedItem = (current.content as? FilesContent.Ready)?.items?.firstOrNull { it.id.value == unsupported }
    if (unsupportedItem != null) {
        BackHandler { unsupported = null }
        TvUnsupportedFileScreen(item = unsupportedItem, onBack = { unsupported = null }, modifier = modifier)
        return
    }

    // Empty and failed folders have no rows, so the header's Refresh takes focus instead.
    val refreshFocus = remember { FocusRequester() }
    val content = current.content
    val headerOwnsFocus = content is FilesContent.Empty && content.paging !is FilesPaging.Available
    LaunchedEffect(current.folder.id.value, headerOwnsFocus) {
        if (headerOwnsFocus) refreshFocus.requestFocus()
    }
    Column(modifier = modifier.fillMaxSize()) {
        TvFilesHeader(state, onEvent, refreshFocus)
        when (content) {
            is FilesContent.Loading -> TvStatusScreen(stringResource(R.string.tv_files_loading))
            is FilesContent.Failed ->
                TvStatusScreen(
                    title = stringResource(R.string.tv_error_title),
                    message = stringResource(content.failure.tvMessage()),
                    action = stringResource(R.string.tv_files_retry),
                    onAction = { onEvent(FilesBrowserEvent.Retry) },
                )
            is FilesContent.Empty ->
                TvStatusScreen(
                    title = stringResource(R.string.tv_files_empty),
                    action = stringResource(R.string.tv_files_load_more).takeIf { content.paging is FilesPaging.Available },
                    onAction = { onEvent(FilesBrowserEvent.LoadNextPage) },
                    modifier = Modifier.weight(1f),
                )
            is FilesContent.Ready ->
                TvFilesList(
                    folderId = current.folder.id.value,
                    content = content,
                    pagingEnabled = current.operation == FilesFolderOperation.Idle,
                    onEvent = onEvent,
                    onOpen = { item ->
                        when {
                            item.isFolder -> onEvent(FilesBrowserEvent.OpenFolder(item.id))
                            item.isPlayable -> onPlayMedia(item)
                            else -> unsupported = item.id.value
                        }
                    },
                )
        }
    }
}

@Composable
private fun TvFilesHeader(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Boolean,
    refreshFocus: FocusRequester,
) {
    val current = state.current
    val enabled = current.operation == FilesFolderOperation.Idle && current.content !is FilesContent.Loading
    var sorting by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = current.folder.name ?: stringResource(R.string.tv_files_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.focusGroup(),
        ) {
            TvButton(
                onClick = { onEvent(FilesBrowserEvent.Refresh) },
                enabled = enabled,
                modifier = Modifier.focusRequester(refreshFocus),
            ) {
                Icon(painterResource(R.drawable.ic_ph_arrow_clockwise), contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tv_files_refresh))
            }
            TvButton(onClick = { sorting = true }, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_ph_sort_ascending), contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(current.folder.sort?.tvLabel() ?: R.string.tv_files_sort))
            }
        }
    }
    val refreshing = (current.operation as? FilesFolderOperation.Loading)
        ?.let { it.intent == FilesFolderOperationIntent.Refresh || it.intent is FilesFolderOperationIntent.Sort }
        ?: false
    if (refreshing) {
        Text(
            text = stringResource(R.string.tv_files_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    if (sorting) {
        TvSortDialog(
            selected = current.folder.sort,
            onSelect = { sort ->
                sorting = false
                onEvent(FilesBrowserEvent.SelectSort(sort))
            },
            onDismiss = { sorting = false },
        )
    }
}

@Composable
private fun TvFilesList(
    folderId: Long,
    content: FilesContent.Ready,
    pagingEnabled: Boolean,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onOpen: (FilesItem) -> Unit,
) {
    val viewport = content.viewport
    val listState = key(folderId) {
        rememberLazyListState(viewport.firstVisibleItemIndex, viewport.firstVisibleItemScrollOffset)
    }
    val currentOnEvent by rememberUpdatedState(onEvent)
    val firstRowFocus = remember(folderId) { FocusRequester() }
    // A folder entered for the first time starts on its first row. After Back the reducer
    // restores the viewport and focusRestorer returns to the row that was focused, so the
    // request must not fire: the first row may not even be composed.
    LaunchedEffect(folderId, listState) {
        if (viewport != FilesViewportPosition()) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        firstRowFocus.requestFocus()
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            if (listState.isScrollInProgress) {
                null
            } else {
                FilesViewportPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
        }
            .filterNotNull()
            .drop(1)
            .distinctUntilChanged()
            .collect { currentOnEvent(FilesBrowserEvent.ViewportChanged(it)) }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusRestorer(firstRowFocus)
            .focusGroup()
            .testTag(TV_FILES_LIST_TAG),
    ) {
        items(items = content.items, key = { it.id.value }) { item ->
            TvFilesRow(
                item = item,
                onClick = { onOpen(item) },
                modifier = if (item.id == content.items.first().id) Modifier.focusRequester(firstRowFocus) else Modifier,
            )
        }
        if (content.paging != FilesPaging.Complete) {
            item(key = TV_FILES_PAGING_KEY) {
                TvFilesPaging(content.paging, pagingEnabled, onEvent)
            }
        }
    }
}

@Composable
internal fun TvFilesRow(
    item: FilesItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val size = remember(item.sizeBytes) { Formatter.formatShortFileSize(context, item.sizeBytes.coerceAtLeast(0L)) }
    val label = when {
        item.isFolder -> stringResource(R.string.tv_files_open_folder, item.name)
        item.isPlayable -> stringResource(R.string.tv_files_play_media, item.name)
        else -> item.name
    }
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(size, maxLines = 1)
                item.playback?.takeIf { it.isWatched }?.let { TvWatchedIndicator(it) }
            }
        },
        leadingContent = {
            Icon(
                painter = painterResource(fileTypeIconRes(item.type)),
                contentDescription = null,
                tint = PutioDesignTokens.yellowSolid,
                modifier = Modifier.size(ListItemDefaults.IconSize),
            )
        },
        trailingContent = if (item.isFolder) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_caret_right),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            null
        },
        scale = ListItemDefaults.scale(focusedScale = FULL_WIDTH_FOCUSED_SCALE),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .testTag(TV_FILES_ROW_TAG),
    )
}

@Composable
private fun TvWatchedIndicator(progress: FilesPlaybackProgress) {
    val fraction = progress.fraction
    val label = if (fraction == null) {
        stringResource(R.string.tv_files_watched)
    } else {
        stringResource(R.string.tv_files_watched_percent, (fraction * PERCENT_SCALE).roundToInt())
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fraction != null) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clearAndSetSemantics {},
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TvFilesPaging(
    paging: FilesPaging,
    enabled: Boolean,
    onEvent: (FilesBrowserEvent) -> Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (paging) {
            is FilesPaging.Available ->
                TvButton(onClick = { onEvent(FilesBrowserEvent.LoadNextPage) }, enabled = enabled) {
                    Text(stringResource(R.string.tv_files_load_more))
                }
            is FilesPaging.Loading ->
                // Disabled rather than removed, so D-pad focus has somewhere to stay.
                TvButton(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.tv_files_loading_more))
                }
            is FilesPaging.Failed -> {
                Text(stringResource(R.string.tv_files_paging_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TvButton(onClick = { onEvent(FilesBrowserEvent.Retry) }, enabled = enabled) {
                    Text(stringResource(R.string.tv_files_retry))
                }
            }
            FilesPaging.Complete -> Unit
        }
    }
}

@Composable
private fun TvUnsupportedFileScreen(
    item: FilesItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_x_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(56.dp),
            )
            Text(
                text = stringResource(R.string.tv_files_unsupported_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = stringResource(R.string.tv_files_unsupported_message),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            val backFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { backFocus.requestFocus() }
            TvButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(top = 40.dp)
                    .focusRequester(backFocus),
            ) {
                Text(stringResource(R.string.tv_files_go_back))
            }
        }
    }
}

private const val TV_FILES_PAGING_KEY = "tv-files-paging"
private const val FULL_WIDTH_FOCUSED_SCALE = 1.02f
private const val PERCENT_SCALE = 100f
