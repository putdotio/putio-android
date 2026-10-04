package io.putdotio.android.downloads

import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import io.putdotio.android.MobileEmptyState
import io.putdotio.android.R
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPlaybackProgress
import kotlin.math.roundToInt

internal const val MOBILE_DOWNLOADS_ROUTE = "account/downloads"
internal const val MOBILE_DOWNLOADS_LIST_TAG = "mobile-downloads-list"
internal const val MOBILE_DOWNLOADS_ITEM_SHEET_TAG = "mobile-downloads-item-sheet"
internal const val MOBILE_DOWNLOADS_REMOVE_CONFIRM_TAG = "mobile-downloads-remove-confirm"
internal const val MOBILE_DOWNLOADS_CONCURRENCY_TAG = "mobile-downloads-concurrency"
internal const val MOBILE_DOWNLOADS_NOTIFICATIONS_TAG = "mobile-downloads-notifications"
internal const val MOBILE_DOWNLOADS_SELECT_TAG = "mobile-downloads-select"
internal const val MOBILE_DOWNLOADS_SELECTION_DELETE_TAG = "mobile-downloads-selection-delete"

@Composable
internal fun MobileDownloadsScreen(
    state: DownloadsState,
    onEvent: (DownloadsEvent) -> Boolean,
    onPlay: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
    onShare: ((FilesItem) -> Unit)? = null,
    notifications: DownloadNotificationAccess = rememberDownloadNotificationAccess(),
) {
    var sheetFileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selected by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var choosingConcurrency by rememberSaveable { mutableStateOf(false) }
    val sheetEntry = state.entries.firstOrNull { it.fileId.value == sheetFileId }
    // Rows that left the store, or started deleting, leave the selection.
    val selectable = state.entries.filterNot { it.fileId in state.removing }.map { it.fileId.value }
    val selection = selected.filter { it in selectable }.toSet()
    LifecycleStartEffect(onEvent) {
        onEvent(DownloadsEvent.Shown)
        onStopOrDispose { onEvent(DownloadsEvent.Hidden) }
    }
    LaunchedEffect(state.focus) {
        val focus = state.focus ?: return@LaunchedEffect
        if (state.entry(focus) != null) sheetFileId = focus.value
        onEvent(DownloadsEvent.FocusHandled)
    }
    BackHandler(enabled = selection.isNotEmpty()) { selected = emptyList() }
    val rows = DownloadRows(
        state = state,
        selection = selection,
        onPlay = onPlay,
        onOpenSheet = { sheetFileId = it.fileId.value },
        onToggle = { entry ->
            val id = entry.fileId.value
            selected = if (id in selection) (selection - id).toList() else (selection + id).toList()
        },
    )
    LazyColumn(modifier.fillMaxSize().testTag(MOBILE_DOWNLOADS_LIST_TAG)) {
        if (selection.isEmpty()) {
            item(key = "summary") {
                DownloadsSummary(state, canSelect = selectable.isNotEmpty()) { selected = selectable.take(1) }
            }
        } else {
            stickyHeader(key = "selection") {
                SelectionBar(
                    count = selection.size,
                    allSelected = selection.size == selectable.size,
                    onSelectAll = { selected = selectable },
                    onClear = { selected = emptyList() },
                    onDelete = {
                        onEvent(DownloadsEvent.RequestRemoval(selection.mapTo(mutableSetOf(), ::FilesItemId)))
                    },
                )
            }
        }
        if (!notifications.enabled) {
            item(key = "notifications") { NotificationsOffNotice(notifications.turnOn) }
        }
        item(key = "concurrency") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.mobile_downloads_concurrency)) },
                supportingContent = {
                    Text(stringResource(R.string.mobile_downloads_concurrency_value, state.concurrency))
                },
                modifier = Modifier
                    .clickable(role = Role.Button) { choosingConcurrency = true }
                    .testTag(MOBILE_DOWNLOADS_CONCURRENCY_TAG),
            )
            HorizontalDivider()
        }
        if (state.entries.isEmpty()) {
            item(key = "empty") {
                MobileEmptyState(
                    stringResource(R.string.mobile_downloads_empty_title),
                    stringResource(R.string.mobile_downloads_empty_message),
                )
            }
        }
        val queue = state.queue
        val running = queue.count { it.status is DownloadStatus.Downloading }
        section("queue", R.string.mobile_downloads_section_queue, queue, rows) {
            stringResource(
                R.string.mobile_downloads_section_queue_detail,
                stringResource(R.string.mobile_downloads_section_queue_running, running),
                stringResource(R.string.mobile_downloads_section_queue_waiting, queue.size - running),
            )
        }
        section("attention", R.string.mobile_downloads_section_attention, state.needsAttention, rows)
        section("on-device", R.string.mobile_downloads_section_on_device, state.onDevice, rows)
    }
    sheetEntry?.takeIf { selection.isEmpty() }?.let { entry ->
        MobileDownloadItemSheet(
            entry = entry,
            removing = state.removing.contains(entry.fileId),
            onEvent = onEvent,
            onPlay = { onPlay(entry.toFilesItem()) },
            onShare = onShare?.let { share -> { share(entry.toFilesItem()) } },
            onDismiss = { sheetFileId = null },
        )
    }
    if (choosingConcurrency) {
        ConcurrencyDialog(
            current = state.concurrency,
            onChoose = { limit ->
                choosingConcurrency = false
                onEvent(DownloadsEvent.SetConcurrency(limit))
            },
            onDismiss = { choosingConcurrency = false },
        )
    }
    state.removal?.let { removal ->
        RemovalDialog(
            removal = removal,
            onConfirm = {
                selected = emptyList()
                onEvent(DownloadsEvent.ConfirmRemoval)
            },
            onCancel = { onEvent(DownloadsEvent.CancelRemoval) },
        )
    }
}

/** What each row needs from the screen, so every section draws rows the same way. */
private class DownloadRows(
    val state: DownloadsState,
    val selection: Set<Long>,
    val onPlay: (FilesItem) -> Unit,
    val onOpenSheet: (DownloadEntry) -> Unit,
    val onToggle: (DownloadEntry) -> Unit,
)

private fun LazyListScope.section(
    key: String,
    title: Int,
    entries: List<DownloadEntry>,
    rows: DownloadRows,
    detail: (@Composable () -> String)? = null,
) {
    if (entries.isEmpty()) return
    item(key = "header-$key") {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            detail?.let {
                Text(
                    it(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    items(entries, key = { "$key-${it.fileId.value}" }) { entry ->
        val removing = entry.fileId in rows.state.removing
        MobileDownloadRow(
            entry = entry,
            queuePosition = rows.state.queuePosition(entry.fileId),
            removing = removing,
            selected = if (rows.selection.isEmpty() || removing) null else entry.fileId.value in rows.selection,
            onOpen = when {
                removing -> null
                rows.state.isAvailableOffline(entry.fileId) -> { { rows.onPlay(entry.toFilesItem()) } }
                else -> { { rows.onOpenSheet(entry) } }
            },
            onActions = { rows.onOpenSheet(entry) },
            onSelect = if (removing) null else { { rows.onToggle(entry) } },
        )
        HorizontalDivider(Modifier.padding(start = 72.dp))
    }
}

@Composable
private fun DownloadsSummary(state: DownloadsState, canSelect: Boolean, onSelect: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(
                R.string.mobile_downloads_storage,
                Formatter.formatShortFileSize(context, state.storageBytes),
            ))
            Text(
                stringResource(R.string.mobile_downloads_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canSelect) {
            TextButton(onClick = onSelect, modifier = Modifier.testTag(MOBILE_DOWNLOADS_SELECT_TAG)) {
                Text(stringResource(R.string.mobile_downloads_select))
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClear) {
                Icon(
                    painterResource(R.drawable.ic_ph_x),
                    contentDescription = stringResource(R.string.mobile_downloads_selection_clear),
                )
            }
            Text(
                pluralStringResource(R.plurals.mobile_downloads_selected, count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (!allSelected) {
                TextButton(onClick = onSelectAll) { Text(stringResource(R.string.mobile_downloads_select_all)) }
            }
            TextButton(onClick = onDelete, modifier = Modifier.testTag(MOBILE_DOWNLOADS_SELECTION_DELETE_TAG)) {
                Text(stringResource(R.string.mobile_downloads_remove_confirm), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun NotificationsOffNotice(onTurnOn: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.mobile_downloads_notifications_off_title)) },
        supportingContent = { Text(stringResource(R.string.mobile_downloads_notifications_off_message)) },
        trailingContent = {
            TextButton(onClick = onTurnOn) { Text(stringResource(R.string.mobile_downloads_notifications_turn_on)) }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.padding(horizontal = 16.dp).testTag(MOBILE_DOWNLOADS_NOTIFICATIONS_TAG),
    )
}

@Composable
private fun MobileDownloadRow(
    entry: DownloadEntry,
    queuePosition: Int?,
    removing: Boolean,
    /** Null outside selection; otherwise whether this row is selected. */
    selected: Boolean?,
    onOpen: (() -> Unit)?,
    onActions: () -> Unit,
    onSelect: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { DownloadRowStatus(entry.status, queuePosition, removing) },
        leadingContent = {
            if (selected != null) {
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                FileTypeIcon(entry.type, Modifier.size(24.dp))
            }
        },
        trailingContent = if (selected == null && !removing) {
            {
                IconButton(onClick = onActions) {
                    Icon(
                        painterResource(R.drawable.ic_ph_dots_three_vertical),
                        contentDescription = stringResource(R.string.mobile_downloads_actions_named, entry.name),
                    )
                }
            }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth().downloadRowInteraction(
            selected = selected,
            onOpen = onOpen,
            onSelect = onSelect,
            openLabel = if (entry.isCompleted) {
                stringResource(R.string.mobile_downloads_play)
            } else {
                stringResource(R.string.mobile_downloads_actions_named, entry.name)
            },
            selectLabel = stringResource(R.string.mobile_downloads_select),
        ),
    )
}

/** In selection a tap toggles the row; otherwise it plays or opens the row, and a long press selects. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.downloadRowInteraction(
    selected: Boolean?,
    onOpen: (() -> Unit)?,
    onSelect: (() -> Unit)?,
    openLabel: String,
    selectLabel: String,
): Modifier = when {
    selected != null && onSelect != null ->
        toggleable(value = selected, role = Role.Checkbox, onValueChange = { onSelect() })
    onOpen != null -> combinedClickable(
        role = Role.Button,
        onClickLabel = openLabel,
        onClick = onOpen,
        onLongClickLabel = selectLabel,
        onLongClick = onSelect,
    )
    else -> this
}

@Composable
private fun DownloadRowStatus(status: DownloadStatus, queuePosition: Int?, removing: Boolean) {
    val context = LocalContext.current
    val failed = status is DownloadStatus.Failed || status == DownloadStatus.Missing
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (removing) {
                stringResource(R.string.mobile_downloads_status_removing)
            } else {
                status.description(context, queuePosition)
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (failed && !removing) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        if (status is DownloadStatus.Downloading && !removing) {
            val percent = status.percent
            if (percent != null) {
                LinearProgressIndicator(progress = { (percent / PERCENT).coerceIn(0f, 1f) })
            } else {
                LinearProgressIndicator()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileDownloadItemSheet(
    entry: DownloadEntry,
    removing: Boolean,
    onEvent: (DownloadsEvent) -> Boolean,
    onPlay: () -> Unit,
    onShare: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(MOBILE_DOWNLOADS_ITEM_SHEET_TAG),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            if (entry.canRetry && !removing) {
                Text(
                    entry.status.description(LocalContext.current),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                )
            }
            if (entry.isCompleted && !removing) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.mobile_downloads_play)) },
                    modifier = Modifier.clickable(role = Role.Button) {
                        onDismiss()
                        onPlay()
                    },
                )
            }
            if (onShare != null && !removing) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.mobile_files_share)) },
                    supportingContent = { Text(stringResource(R.string.mobile_files_share_description)) },
                    modifier = Modifier.clickable(role = Role.Button) {
                        onDismiss()
                        onShare()
                    },
                )
            }
            if (entry.canRetry && !removing) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(
                            if (entry.status == DownloadStatus.Missing) {
                                R.string.mobile_downloads_download_again
                            } else {
                                R.string.mobile_action_retry
                            },
                        ))
                    },
                    modifier = Modifier.clickable(role = Role.Button) {
                        onDismiss()
                        onEvent(DownloadsEvent.Retry(entry.fileId))
                    },
                )
            }
            HorizontalDivider()
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.mobile_downloads_remove), color = MaterialTheme.colorScheme.error)
                },
                modifier = Modifier
                    .clickable(enabled = !removing, role = Role.Button) {
                        onDismiss()
                        onEvent(DownloadsEvent.RequestRemoval(setOf(entry.fileId)))
                    }
                    .padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun ConcurrencyDialog(current: Int, onChoose: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mobile_downloads_concurrency)) },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(
                    stringResource(R.string.mobile_downloads_concurrency_message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (limit in DOWNLOAD_CONCURRENCY_CHOICES) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = limit == current, role = Role.RadioButton) { onChoose(limit) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = limit == current, onClick = null)
                        Spacer(Modifier.size(16.dp))
                        Text(stringResource(R.string.mobile_downloads_concurrency_value, limit))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mobile_action_cancel)) }
        },
    )
}

@Composable
private fun RemovalDialog(removal: DownloadRemoval, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val count = removal.fileIds.size
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (removal.name != null) {
                    stringResource(R.string.mobile_downloads_remove_title)
                } else {
                    pluralStringResource(R.plurals.mobile_downloads_remove_many_title, count, count)
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    removal.name?.let { stringResource(R.string.mobile_downloads_remove_message, it) }
                        ?: stringResource(R.string.mobile_downloads_remove_many_message),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(MOBILE_DOWNLOADS_REMOVE_CONFIRM_TAG)) {
                Text(stringResource(R.string.mobile_downloads_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.mobile_action_cancel)) }
        },
    )
}

internal fun DownloadStatus.description(context: Context, queuePosition: Int? = null): String =
    when (this) {
        DownloadStatus.Queued -> queuePosition?.let {
            context.getString(R.string.mobile_downloads_status_queued_position, it)
        } ?: context.getString(R.string.mobile_downloads_status_queued)
        is DownloadStatus.Paused -> context.getString(
            when (reason) {
                DownloadPauseReason.NETWORK -> R.string.mobile_downloads_status_waiting_network
                DownloadPauseReason.STORAGE -> R.string.mobile_downloads_status_waiting_storage
            },
            Formatter.formatShortFileSize(context, bytesDownloaded),
        )
        is DownloadStatus.Downloading -> percent?.let {
            val rounded = it.roundToInt().coerceIn(0, PERCENT.toInt())
            context.getString(R.string.mobile_downloads_status_downloading, rounded)
        } ?: context.getString(
            R.string.mobile_downloads_status_downloading_unknown,
            Formatter.formatShortFileSize(context, bytesDownloaded),
        )
        is DownloadStatus.Completed ->
            context.getString(R.string.mobile_downloads_status_completed, Formatter.formatShortFileSize(context, bytes))
        is DownloadStatus.Failed -> context.getString(reason.message())
        DownloadStatus.Missing -> context.getString(R.string.mobile_downloads_status_missing)
    }

/** The player route needs a Files item shape; downloads keep the fields it reads. */
internal fun DownloadEntry.toFilesItem(): FilesItem =
    FilesItem(
        id = fileId,
        parentId = null,
        name = name,
        type = type,
        sizeBytes = (status as? DownloadStatus.Completed)?.bytes ?: 0L,
        createdAt = "",
        playback = FilesPlaybackProgress(startFromSeconds, durationSeconds),
    )

private const val PERCENT = 100f
