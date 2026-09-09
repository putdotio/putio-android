package io.putdotio.android

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.downloads.DownloadEntry
import io.putdotio.android.downloads.DownloadFailureReason
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.DownloadsEvent
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.files.FilesItem
import kotlin.math.roundToInt

internal const val MOBILE_DOWNLOADS_ROUTE = "account/downloads"
internal const val MOBILE_DOWNLOADS_LIST_TAG = "mobile-downloads-list"
internal const val MOBILE_DOWNLOADS_ITEM_SHEET_TAG = "mobile-downloads-item-sheet"
internal const val MOBILE_DOWNLOADS_REMOVE_CONFIRM_TAG = "mobile-downloads-remove-confirm"

@Composable
internal fun MobileDownloadsScreen(
    state: DownloadsState,
    onEvent: (DownloadsEvent) -> Boolean,
    onPlay: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetFileId by rememberSaveable { mutableStateOf<Long?>(null) }
    val sheetEntry = state.entries.firstOrNull { it.fileId.value == sheetFileId }
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize().testTag(MOBILE_DOWNLOADS_LIST_TAG)) {
        item(key = "summary") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
        if (state.entries.isEmpty()) {
            item(key = "empty") {
                MobileEmptyState(
                    stringResource(R.string.mobile_downloads_empty_title),
                    stringResource(R.string.mobile_downloads_empty_message),
                )
            }
        }
        items(state.entries, key = { it.fileId.value }) { entry ->
            MobileDownloadRow(
                entry = entry,
                onOpen = if (state.isAvailableOffline(entry.fileId)) { { onPlay(entry.toFilesItem()) } } else null,
                onActions = { sheetFileId = entry.fileId.value },
            )
            HorizontalDivider(Modifier.padding(start = 72.dp))
        }
    }
    sheetEntry?.let { entry ->
        MobileDownloadItemSheet(
            entry = entry,
            removing = state.removing.contains(entry.fileId),
            onEvent = onEvent,
            onPlay = { onPlay(entry.toFilesItem()) },
            onDismiss = { sheetFileId = null },
        )
    }
    state.removal?.let { removal ->
        AlertDialog(
            onDismissRequest = { onEvent(DownloadsEvent.CancelRemoval) },
            title = { Text(stringResource(R.string.mobile_downloads_remove_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.mobile_downloads_remove_message, removal.name))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onEvent(DownloadsEvent.ConfirmRemoval) },
                    modifier = Modifier.testTag(MOBILE_DOWNLOADS_REMOVE_CONFIRM_TAG),
                ) { Text(stringResource(R.string.mobile_downloads_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(DownloadsEvent.CancelRemoval) }) {
                    Text(stringResource(R.string.mobile_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun MobileDownloadRow(entry: DownloadEntry, onOpen: (() -> Unit)?, onActions: () -> Unit) {
    val context = LocalContext.current
    val status = entry.status
    val interaction = if (onOpen != null) {
        Modifier.clickable(
            role = Role.Button,
            onClickLabel = stringResource(R.string.mobile_downloads_play),
            onClick = onOpen,
        )
    } else {
        Modifier
    }
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status.description(context), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (status is DownloadStatus.Downloading) {
                    val total = status.totalBytes
                    if (total != null && total > 0L) {
                        LinearProgressIndicator(
                            progress = { (status.bytesDownloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) },
                        )
                    } else {
                        LinearProgressIndicator()
                    }
                }
            }
        },
        leadingContent = { FileTypeIcon(entry.type, Modifier.size(24.dp)) },
        trailingContent = {
            IconButton(onClick = onActions) {
                Icon(
                    painterResource(R.drawable.ic_ph_dots_three_vertical),
                    contentDescription = stringResource(R.string.mobile_downloads_actions_named, entry.name),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().then(interaction),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileDownloadItemSheet(
    entry: DownloadEntry,
    removing: Boolean,
    onEvent: (DownloadsEvent) -> Boolean,
    onPlay: () -> Unit,
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
            if (entry.isCompleted && !removing) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.mobile_downloads_play)) },
                    modifier = Modifier.clickable(role = Role.Button) {
                        onDismiss()
                        onPlay()
                    },
                )
            }
            if (entry.canRetry && !removing) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.mobile_action_retry)) },
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
                        onEvent(DownloadsEvent.RequestRemoval(entry.fileId))
                    }
                    .padding(bottom = 24.dp),
            )
        }
    }
}

internal fun DownloadStatus.description(context: android.content.Context): String =
    when (this) {
        DownloadStatus.Queued -> context.getString(R.string.mobile_downloads_status_queued)
        is DownloadStatus.WaitingForNetwork -> context.getString(
            R.string.mobile_downloads_status_waiting_network,
            Formatter.formatShortFileSize(context, bytesDownloaded),
        )
        is DownloadStatus.Downloading -> {
            val total = totalBytes
            if (total != null && total > 0L) {
                context.getString(
                    R.string.mobile_downloads_status_downloading,
                    (bytesDownloaded * PERCENT / total).toDouble().roundToInt().coerceIn(0, PERCENT.toInt()),
                )
            } else {
                context.getString(
                    R.string.mobile_downloads_status_downloading_unknown,
                    Formatter.formatShortFileSize(context, bytesDownloaded),
                )
            }
        }
        is DownloadStatus.Completed ->
            context.getString(R.string.mobile_downloads_status_completed, Formatter.formatShortFileSize(context, bytes))
        is DownloadStatus.Failed -> context.getString(
            when (reason) {
                DownloadFailureReason.NETWORK -> R.string.mobile_downloads_status_failed_network
                DownloadFailureReason.AUTHENTICATION -> R.string.mobile_downloads_status_failed_authentication
                DownloadFailureReason.UNAVAILABLE -> R.string.mobile_downloads_status_failed_unavailable
                DownloadFailureReason.STORAGE -> R.string.mobile_downloads_status_failed_storage
                DownloadFailureReason.UNEXPECTED -> R.string.mobile_downloads_status_failed_unexpected
            },
        )
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
    )

private const val PERCENT = 100L

