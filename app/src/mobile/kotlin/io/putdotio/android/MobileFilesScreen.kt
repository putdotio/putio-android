package io.putdotio.android

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesViewportPosition
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import java.time.Instant

internal const val MOBILE_FILES_LIST_TAG = "mobile-files-list"
internal const val MOBILE_FILES_REFRESH_TAG = "mobile-files-refresh"

@Composable
internal fun MobileFilesScreen(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state.current
    when (val content = current.content) {
        is FilesContent.Loading ->
            MobileLoadingState(
                message = stringResource(R.string.mobile_state_loading),
                modifier = modifier,
            )

        is FilesContent.Empty -> MobileRefreshableFilesContent(state, content, onEvent, modifier)

        is FilesContent.Failed ->
            MobileErrorState(
                title = stringResource(R.string.mobile_state_error_title),
                message = stringResource(content.failure.messageResource()),
                retryLabel = stringResource(R.string.mobile_action_retry),
                onRetry = { onEvent(FilesBrowserEvent.Retry) },
                modifier = modifier,
            )

        is FilesContent.Ready -> MobileRefreshableFilesContent(state, content, onEvent, modifier)
    }
}

@Composable
private fun MobileRefreshableFilesContent(
    state: FilesBrowserState,
    content: FilesContent,
    onEvent: (FilesBrowserEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val operation = state.current.operation
    val isRefreshing =
        operation is FilesFolderOperation.Loading &&
            operation.intent == FilesFolderOperationIntent.Refresh
    val refreshLabel = stringResource(R.string.mobile_files_refresh)
    val refreshAction = CustomAccessibilityAction(refreshLabel) {
        onEvent(FilesBrowserEvent.Refresh)
        true
    }

    Column(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { onEvent(FilesBrowserEvent.Refresh) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(MOBILE_FILES_REFRESH_TAG)
                .semantics {
                    if (operation !is FilesFolderOperation.Loading) {
                        customActions = listOf(refreshAction)
                    }
                },
        ) {
            when (content) {
                is FilesContent.Empty -> MobileEmptyFilesContent(content.paging, onEvent)
                is FilesContent.Ready ->
                    key(state.current.folder.id.value, state.current.viewportGeneration) {
                        MobileFilesList(content, onEvent)
                    }

                is FilesContent.Failed,
                is FilesContent.Loading,
                -> Unit
            }
        }
        MobileFilesOperationStatus(operation = operation, onRetry = { onEvent(FilesBrowserEvent.Retry) })
    }
}

@Composable
private fun MobileEmptyFilesContent(
    paging: FilesPaging,
    onEvent: (FilesBrowserEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            MobileEmptyState(
                title = stringResource(R.string.mobile_state_empty_title),
                message = stringResource(R.string.mobile_state_empty_message),
            )
        }
        MobileFilesPaging(
            paging = paging,
            onEvent = onEvent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MobileFilesOperationStatus(
    operation: FilesFolderOperation,
    onRetry: () -> Unit,
) {
    when (operation) {
        FilesFolderOperation.Idle -> Unit
        is FilesFolderOperation.Loading -> {
            if (operation.intent is FilesFolderOperationIntent.Sort) {
                val message = stringResource(R.string.mobile_files_sorting)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = message },
                        strokeWidth = 2.dp,
                    )
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        is FilesFolderOperation.Failed -> {
            val message = stringResource(
                if (operation.intent == FilesFolderOperationIntent.Refresh) {
                    R.string.mobile_files_refresh_error
                } else {
                    R.string.mobile_files_sort_error
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.mobile_action_retry))
                }
            }
        }
    }
}

@Composable
private fun MobileFilesList(
    content: FilesContent.Ready,
    onEvent: (FilesBrowserEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewport = content.viewport
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewport.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = viewport.firstVisibleItemScrollOffset,
    )
    val currentOnEvent by rememberUpdatedState(onEvent)

    LaunchedEffect(listState, viewport) {
        if (
            listState.firstVisibleItemIndex != viewport.firstVisibleItemIndex ||
            listState.firstVisibleItemScrollOffset != viewport.firstVisibleItemScrollOffset
        ) {
            listState.scrollToItem(
                index = viewport.firstVisibleItemIndex,
                scrollOffset = viewport.firstVisibleItemScrollOffset,
            )
        }
    }

    LaunchedEffect(listState) {
        // Report only settled positions: per-frame offsets during a fling would
        // rebuild FilesBrowserState and recompose the whole signed-in tree.
        snapshotFlow {
            if (listState.isScrollInProgress) {
                null
            } else {
                FilesViewportPosition(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                )
            }
        }
            .filterNotNull()
            .drop(1)
            .distinctUntilChanged()
            .collect { currentOnEvent(FilesBrowserEvent.ViewportChanged(it)) }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .testTag(MOBILE_FILES_LIST_TAG),
    ) {
        items(
            items = content.items,
            key = { it.id.value },
        ) { item ->
            MobileFilesRow(
                item = item,
                onOpenFolder = { onEvent(FilesBrowserEvent.OpenFolder(it)) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = FILES_DIVIDER_INSET))
        }
        if (content.paging != FilesPaging.Complete) {
            item(key = FILES_PAGING_ITEM_KEY) {
                MobileFilesPaging(
                    paging = content.paging,
                    onEvent = onEvent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MobileFilesRow(
    item: FilesItem,
    onOpenFolder: (FilesItemId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metadata = formatFilesItemMetadata(LocalContext.current, item)
    val interaction = if (item.isFolder) {
        val openFolderLabel = stringResource(R.string.mobile_files_open_folder, item.name)
        Modifier.clickable(
            onClickLabel = openFolderLabel,
            role = Role.Button,
            onClick = { onOpenFolder(item.id) },
        )
    } else {
        Modifier
    }

    ListItem(
        headlineContent = {
            Text(
                text = item.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .then(interaction),
        supportingContent = metadata?.let { value ->
            {
                Text(
                    text = value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier.size(FILES_ICON_CONTAINER_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                FileTypeIcon(
                    type = item.type,
                    modifier = Modifier.size(FILES_ICON_SIZE),
                )
            }
        },
    )
}

@Composable
private fun MobileFilesPaging(
    paging: FilesPaging,
    onEvent: (FilesBrowserEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (paging) {
        is FilesPaging.Available ->
            Box(
                modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(onClick = { onEvent(FilesBrowserEvent.LoadNextPage) }) {
                    Text(stringResource(R.string.mobile_files_load_more))
                }
            }

        is FilesPaging.Loading -> {
            val loadingMessage = stringResource(R.string.mobile_files_loading_more)
            Row(
                modifier = modifier
                    .padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = loadingMessage },
                    strokeWidth = 2.dp,
                )
                Text(loadingMessage)
            }
        }

        is FilesPaging.Failed ->
            Column(
                modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.mobile_files_paging_error),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { onEvent(FilesBrowserEvent.Retry) }) {
                    Text(stringResource(R.string.mobile_action_retry))
                }
            }

        FilesPaging.Complete -> Unit
    }
}

private fun formatFilesItemMetadata(
    context: Context,
    item: FilesItem,
): String? {
    val date = item.createdAt.toDisplayDate(context)
    if (item.isFolder) {
        return date
    }

    val size = Formatter.formatShortFileSize(context, item.sizeBytes.coerceAtLeast(0L))
    return if (date == null) {
        size
    } else {
        context.getString(R.string.mobile_files_metadata_size_date, size, date)
    }
}

private fun String.toDisplayDate(context: Context): String? =
    runCatching(Instant::parse)
        .getOrNull()
        ?.toEpochMilli()
        ?.let { timestamp ->
            DateUtils.formatDateTime(
                context,
                timestamp,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR,
            )
        }

@StringRes
private fun FilesFailure.messageResource(): Int =
    when (this) {
        is FilesFailure.AuthenticationRequired -> R.string.mobile_state_error_session
        is FilesFailure.AccessDenied -> R.string.mobile_state_error_forbidden
        is FilesFailure.RateLimited -> R.string.mobile_state_error_rate_limited
        is FilesFailure.ServerUnavailable -> R.string.mobile_state_error_unavailable
        is FilesFailure.NetworkUnavailable -> R.string.mobile_state_error_message
        is FilesFailure.ApiRejected,
        is FilesFailure.InvalidResponse,
        is FilesFailure.Misconfigured,
        is FilesFailure.Unexpected,
        -> R.string.mobile_state_error_unavailable
    }

private const val FILES_PAGING_ITEM_KEY = "files-paging"
private val FILES_DIVIDER_INSET = 72.dp
private val FILES_ICON_CONTAINER_SIZE = 40.dp
private val FILES_ICON_SIZE = 24.dp
