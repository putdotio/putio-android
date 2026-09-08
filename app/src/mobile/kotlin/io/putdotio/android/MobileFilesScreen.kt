package io.putdotio.android

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.FilesDeleteStatus
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesViewportPosition
import io.putdotio.android.files.canStartOperation
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import java.time.Instant

internal const val MOBILE_FILES_LIST_TAG = "mobile-files-list"
internal const val MOBILE_FILES_OPERATION_RETRY_TAG = "mobile-files-operation-retry"
internal const val MOBILE_FILES_PAGING_ACTION_TAG = "mobile-files-paging-action"
internal const val MOBILE_FILES_REFRESH_TAG = "mobile-files-refresh"
internal const val MOBILE_FILES_WATCHED_TAG = "mobile-files-watched"

@Composable
internal fun MobileFilesScreen(
    state: FilesBrowserState,
    onEvent: (FilesBrowserEvent) -> Unit,
    onPlayMedia: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
    confirmedTrashEnabled: Boolean? = null,
    onMoveItem: ((FilesItem) -> Unit)? = null,
) {
    val current = state.current
    when (val content = current.content) {
        is FilesContent.Loading ->
            MobileLoadingState(
                message = stringResource(R.string.mobile_state_loading),
                modifier = modifier,
            )

        is FilesContent.Failed ->
            MobileErrorState(
                title = stringResource(R.string.mobile_state_error_title),
                message = stringResource(content.failure.mobileMessageResource()),
                retryLabel = stringResource(R.string.mobile_action_retry),
                onRetry = { onEvent(FilesBrowserEvent.Retry) },
                modifier = modifier,
            )

        is FilesContent.Empty,
        is FilesContent.Ready,
        -> key(current.folder.id.value) {
            MobileRefreshableFilesContent(state, content, onEvent, onPlayMedia, modifier, confirmedTrashEnabled, onMoveItem)
        }
    }
}

@Composable
private fun MobileRefreshableFilesContent(
    state: FilesBrowserState,
    content: FilesContent,
    onEvent: (FilesBrowserEvent) -> Unit,
    onPlayMedia: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
    confirmedTrashEnabled: Boolean? = null,
    onMoveItem: ((FilesItem) -> Unit)? = null,
) {
    val operation = state.current.operation
    val currentOperation by rememberUpdatedState(operation)
    var selectedItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedItem = (content as? FilesContent.Ready)?.items?.firstOrNull { it.id.value == selectedItemId }
    LaunchedEffect(selectedItemId, selectedItem) {
        if (selectedItem == null) selectedItemId = null
    }
    if (selectedItem != null) {
        key(selectedItem.id.value) {
            MobileFilesActions(
                item = selectedItem,
                folderId = state.current.folder.id,
                operation = operation,
                renameCompletion = state.current.renameCompletion,
                onEvent = onEvent,
                onDismiss = { selectedItemId = null },
                confirmedTrashEnabled = confirmedTrashEnabled,
                onMoveItem = onMoveItem,
            )
        }
    }
    val isRefreshing =
        operation is FilesFolderOperation.Loading &&
            operation.intent == FilesFolderOperationIntent.Refresh
    val refreshLabel = stringResource(R.string.mobile_files_refresh)
    val refreshAction = CustomAccessibilityAction(refreshLabel) {
        if (selectedItemId == null && currentOperation.canStartOperation) {
            onEvent(FilesBrowserEvent.Refresh)
            true
        } else {
            false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                if (selectedItemId == null && currentOperation.canStartOperation) {
                    onEvent(FilesBrowserEvent.Refresh)
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(MOBILE_FILES_REFRESH_TAG)
                .semantics {
                    if (selectedItemId == null && operation.canStartOperation) {
                        customActions = listOf(refreshAction)
                    }
                },
        ) {
            when (content) {
                is FilesContent.Empty -> MobileEmptyFilesContent(
                    paging = content.paging,
                    pagingEnabled = operation == FilesFolderOperation.Idle,
                    onEvent = onEvent,
                )
                is FilesContent.Ready ->
                    key(state.current.folder.id.value, state.current.viewportGeneration) {
                        MobileFilesList(
                            content = content,
                            pagingEnabled = operation == FilesFolderOperation.Idle,
                            onEvent = onEvent,
                            onPlayMedia = onPlayMedia,
                            onActions = { selectedItemId = it.id.value },
                            operation = operation,
                        )
                    }

                is FilesContent.Failed,
                is FilesContent.Loading,
                -> Unit
            }
        }
        MobileFilesOperationStatus(operation = operation, onRetry = { onEvent(FilesBrowserEvent.Retry) })
        if (operation == FilesFolderOperation.Idle) {
            state.current.deleteOutcome?.let { MobileFilesDeleteStatus(it) }
            state.current.moveOutcome?.let { MobileFilesMoveStatus(it) }
        }
    }
}

@Composable
private fun MobileEmptyFilesContent(
    paging: FilesPaging,
    pagingEnabled: Boolean,
    onEvent: (FilesBrowserEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val viewportHeight = maxHeight
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                MobileEmptyState(
                    title = stringResource(R.string.mobile_state_empty_title),
                    message = stringResource(R.string.mobile_state_empty_message),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = viewportHeight),
                )
            }
        }
        MobileFilesPaging(
            paging = paging,
            enabled = pagingEnabled,
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
            val messageResource = when (operation.intent) {
                FilesFolderOperationIntent.Refresh -> null
                is FilesFolderOperationIntent.Sort -> R.string.mobile_files_sorting
                is FilesFolderOperationIntent.Rename ->
                    if (operation.phase == FilesFolderOperationPhase.RENAMING) {
                        R.string.mobile_files_renaming
                    } else {
                        R.string.mobile_files_rename_reloading
                    }
                is FilesFolderOperationIntent.Delete -> when (operation.phase) {
                    FilesFolderOperationPhase.DELETING -> R.string.mobile_files_deleting
                    FilesFolderOperationPhase.CHECKING_DELETE -> R.string.mobile_files_delete_checking
                    else -> R.string.mobile_files_delete_reloading
                }
                is FilesFolderOperationIntent.Move -> when (operation.phase) {
                    FilesFolderOperationPhase.MOVING -> R.string.mobile_files_moving
                    FilesFolderOperationPhase.CHECKING_MOVE -> R.string.mobile_files_move_checking
                    else -> R.string.mobile_files_move_reloading
                }
            }
            if (messageResource != null) {
                val message = stringResource(messageResource)
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
                when {
                    operation.intent is FilesFolderOperationIntent.Move ->
                        if (operation.phase == FilesFolderOperationPhase.RELOADING) {
                            R.string.mobile_files_move_reload_error
                        } else {
                            R.string.mobile_files_move_unknown
                        }
                    operation.intent is FilesFolderOperationIntent.Delete ->
                        if (operation.phase == FilesFolderOperationPhase.RELOADING) {
                            R.string.mobile_files_delete_reload_error
                        } else {
                            R.string.mobile_files_delete_unknown
                        }
                    operation.intent == FilesFolderOperationIntent.Refresh -> R.string.mobile_files_refresh_error
                    operation.phase == FilesFolderOperationPhase.PERSISTING_SORT -> R.string.mobile_files_sort_error
                    operation.phase == FilesFolderOperationPhase.RENAMING -> R.string.mobile_files_rename_error
                    operation.intent is FilesFolderOperationIntent.Rename -> R.string.mobile_files_rename_reload_error
                    else -> R.string.mobile_files_reload_error
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
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag(MOBILE_FILES_OPERATION_RETRY_TAG),
                ) {
                    Text(stringResource(
                        if ((operation.intent is FilesFolderOperationIntent.Delete ||
                                operation.intent is FilesFolderOperationIntent.Move) &&
                            operation.phase != FilesFolderOperationPhase.RELOADING
                        ) {
                            R.string.mobile_files_check_status
                        } else {
                            R.string.mobile_action_retry
                        },
                    ))
                }
            }
        }
    }
}

@Composable
private fun MobileFilesDeleteStatus(outcome: FilesDeleteOutcome) {
    val message = when (outcome.status) {
        FilesDeleteStatus.NO_LONGER_AVAILABLE -> R.string.mobile_files_delete_unavailable
        FilesDeleteStatus.STILL_PRESENT -> R.string.mobile_files_delete_still_present
        FilesDeleteStatus.SKIPPED -> R.string.mobile_files_delete_skipped
        FilesDeleteStatus.CHECKING, FilesDeleteStatus.UNKNOWN -> null
    }
    if (message != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(stringResource(message, outcome.itemName), style = MaterialTheme.typography.bodyMedium)
            if (outcome.status != FilesDeleteStatus.NO_LONGER_AVAILABLE) {
                outcome.failure?.let {
                    Text(
                        stringResource(it.mobileMessageResource()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileFilesList(
    content: FilesContent.Ready,
    pagingEnabled: Boolean,
    onEvent: (FilesBrowserEvent) -> Unit,
    onPlayMedia: (FilesItem) -> Unit,
    onActions: (FilesItem) -> Unit,
    operation: FilesFolderOperation,
    modifier: Modifier = Modifier,
) {
    val viewport = content.viewport
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewport.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = viewport.firstVisibleItemScrollOffset,
    )
    val currentOnEvent by rememberUpdatedState(onEvent)
    val pendingIntent = when (operation) {
        is FilesFolderOperation.Loading -> operation.intent.takeIf {
            operation.phase == FilesFolderOperationPhase.RELOADING || it is FilesFolderOperationIntent.Delete ||
                it is FilesFolderOperationIntent.Move
        }
        is FilesFolderOperation.Failed -> operation.intent.takeIf {
            operation.phase == FilesFolderOperationPhase.RELOADING || it is FilesFolderOperationIntent.Delete ||
                it is FilesFolderOperationIntent.Move
        }
        FilesFolderOperation.Idle -> null
    }
    val pendingItemId = when (pendingIntent) {
        is FilesFolderOperationIntent.Rename -> pendingIntent.itemId
        is FilesFolderOperationIntent.Delete -> pendingIntent.itemId
        is FilesFolderOperationIntent.Move -> pendingIntent.itemId
        else -> null
    }
    val actionsEnabled = operation.canStartOperation

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
                onActions = if (item.id.value > 0L) { { onActions(item) } } else null,
                actionsEnabled = actionsEnabled,
                onClick = when {
                    item.id == pendingItemId -> null
                    item.isFolder -> {
                        { onEvent(FilesBrowserEvent.OpenFolder(item.id)) }
                    }

                    item.isPlayable -> {
                        { onPlayMedia(item) }
                    }

                    else -> null
                },
                onClickLabel = when {
                    item.isFolder -> stringResource(R.string.mobile_files_open_folder, item.name)
                    item.isPlayable -> stringResource(R.string.mobile_files_play_media, item.name)
                    else -> null
                },
            )
            HorizontalDivider(modifier = Modifier.padding(start = FILES_DIVIDER_INSET))
        }
        if (content.paging != FilesPaging.Complete) {
            item(key = FILES_PAGING_ITEM_KEY) {
                MobileFilesPaging(
                    paging = content.paging,
                    enabled = pagingEnabled,
                    onEvent = onEvent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun MobileFilesRow(
    item: FilesItem,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onActions: (() -> Unit)? = null,
    actionsEnabled: Boolean = true,
) {
    val metadata = formatFilesItemMetadata(LocalContext.current, item)
    val actionsLabel = stringResource(R.string.mobile_files_actions, item.name)
    val interaction = if (onActions != null && actionsEnabled) {
        Modifier.combinedClickable(
            onClickLabel = onClickLabel,
            role = Role.Button,
            onClick = onClick ?: onActions,
            onLongClickLabel = actionsLabel,
            onLongClick = onActions,
        )
    } else if (onClick != null) {
        Modifier.clickable(
            onClickLabel = onClickLabel,
            role = Role.Button,
            onClick = onClick,
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
        supportingContent = if (metadata == null && item.playback?.isWatched != true) {
            null
        } else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(FILES_PROGRESS_SPACING)) {
                    metadata?.let { value ->
                        Text(
                            text = value,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item.playback?.takeIf { it.isWatched }?.let { MobileFilesWatchedIndicator(it) }
                }
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
        trailingContent = onActions?.let { action ->
            {
                IconButton(onClick = action, enabled = actionsEnabled) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ph_dots_three_vertical),
                        contentDescription = actionsLabel,
                    )
                }
            }
        },
    )
}

// Known duration draws a determinate bar; an unknown duration still marks the row watched.
// The bar is decorative: the label text is the accessible content and merges into the row
// node, so TalkBack reads name, metadata, and watched state as one item.
@Composable
private fun MobileFilesWatchedIndicator(progress: FilesPlaybackProgress) {
    val fraction = progress.fraction
    val label = if (fraction == null) {
        stringResource(R.string.mobile_files_watched)
    } else {
        stringResource(R.string.mobile_files_watched_percent, (fraction * PERCENT_SCALE).roundToInt())
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MOBILE_FILES_WATCHED_TAG),
        horizontalArrangement = Arrangement.spacedBy(FILES_PROGRESS_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = FILES_PROGRESS_HEIGHT)
                    .clearAndSetSemantics {},
                drawStopIndicator = {},
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun MobileFilesPaging(
    paging: FilesPaging,
    enabled: Boolean,
    onEvent: (FilesBrowserEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (paging) {
        is FilesPaging.Available ->
            Box(
                modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    onClick = { onEvent(FilesBrowserEvent.LoadNextPage) },
                    enabled = enabled,
                    modifier = Modifier.testTag(MOBILE_FILES_PAGING_ACTION_TAG),
                ) {
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
                TextButton(
                    onClick = { onEvent(FilesBrowserEvent.Retry) },
                    enabled = enabled,
                    modifier = Modifier.testTag(MOBILE_FILES_PAGING_ACTION_TAG),
                ) {
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
internal fun FilesFailure.mobileMessageResource(): Int =
    when (this) {
        FilesFailure.NavigationBlocked -> R.string.mobile_navigation_blocked
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
private val FILES_PROGRESS_SPACING = 6.dp
private val FILES_PROGRESS_HEIGHT = 4.dp
private const val PERCENT_SCALE = 100f
