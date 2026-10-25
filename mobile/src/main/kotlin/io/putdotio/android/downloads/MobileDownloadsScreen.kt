package io.putdotio.android.downloads

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import io.putdotio.android.MobileEmptyState
import io.putdotio.android.MobileMiddleCutText
import io.putdotio.android.MobileRow
import io.putdotio.android.MobileSectionLabel
import io.putdotio.android.R
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioDestructiveButton
import io.putdotio.android.design.putioMono
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.playbackContainer
import io.putdotio.android.neutralTextButtonColors
import kotlin.math.roundToInt

internal const val MOBILE_DOWNLOADS_ROUTE = "downloads"
internal const val MOBILE_DOWNLOADS_LIST_TAG = "mobile-downloads-list"
internal const val MOBILE_DOWNLOADS_ITEM_SHEET_TAG = "mobile-downloads-item-sheet"
internal const val MOBILE_DOWNLOADS_REMOVE_CONFIRM_TAG = "mobile-downloads-remove-confirm"
internal const val MOBILE_DOWNLOADS_NOTIFICATIONS_TAG = "mobile-downloads-notifications"

/**
 * The Downloads tab's list. Its title, overflow and selection bar sit in the shell's top bar
 * ([MobileDownloadsTopBar]); both read the same [selection].
 */
@Composable
internal fun MobileDownloadsScreen(
    state: DownloadsState,
    onEvent: (DownloadsEvent) -> Boolean,
    onPlay: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
    onShare: ((FilesItem) -> Unit)? = null,
    notifications: DownloadNotificationAccess = rememberDownloadNotificationAccess(),
    selection: DownloadsSelection = rememberDownloadsSelection(),
) {
    var sheetFileId by rememberSaveable { mutableStateOf<Long?>(null) }
    val sheetEntry = state.entries.firstOrNull { it.fileId.value == sheetFileId }
    val inSelection = selection.isActive(state)
    var toolbarHeight by remember { mutableIntStateOf(0) }
    val toolbarClearance = with(LocalDensity.current) { toolbarHeight.toDp() } + TOOLBAR_CLEARANCE
    val selected = selection.selected(state)
    LifecycleStartEffect(onEvent) {
        onEvent(DownloadsEvent.Shown)
        onStopOrDispose { onEvent(DownloadsEvent.Hidden) }
    }
    LaunchedEffect(state.focus) {
        val focus = state.focus ?: return@LaunchedEffect
        if (state.entry(focus) != null) sheetFileId = focus.value
        onEvent(DownloadsEvent.FocusHandled)
    }
    BackHandler(enabled = inSelection, onBack = selection::end)
    val rows = DownloadRows(
        state = state,
        selection = selected.takeIf { inSelection },
        onEvent = onEvent,
        onPlay = onPlay,
        onOpenSheet = { sheetFileId = it.fileId.value },
        onToggle = { selection.toggle(it.fileId) },
    )
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(MOBILE_DOWNLOADS_LIST_TAG),
            // The last row scrolls clear of the floating toolbar, however tall large fonts make it.
            contentPadding = PaddingValues(bottom = if (inSelection) toolbarClearance else 0.dp),
        ) {
            if (!notifications.enabled) {
                item(key = "notifications") { NotificationsOffNotice(notifications.turnOn) }
            }
            if (state.entries.isEmpty()) {
                item(key = "empty") {
                    MobileEmptyState(
                        stringResource(R.string.mobile_downloads_empty_title),
                        stringResource(R.string.mobile_downloads_empty_message),
                    )
                }
            }
            val sections = listOf(
                DownloadSection("downloading", R.string.mobile_downloads_section_downloading, state.queue),
                DownloadSection("attention", R.string.mobile_downloads_section_attention, state.needsAttention),
                DownloadSection("on-device", R.string.mobile_downloads_section_on_device, state.onDevice),
            ).filter { it.entries.isNotEmpty() }
            // Under the tab title the first label sits closer; the selection bar has no title spacing.
            sections.forEachIndexed { index, section ->
                section(section, rows, first = index == 0 && notifications.enabled && !inSelection)
            }
        }
        if (inSelection) {
            DownloadsSelectionToolbar(
                state = state,
                selected = selected,
                onEvent = onEvent,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = TOOLBAR_OFFSET)
                    .onSizeChanged { toolbarHeight = it.height },
            )
        }
    }
    sheetEntry?.takeIf { !inSelection }?.let { entry ->
        MobileDownloadItemSheet(
            entry = entry,
            removing = state.removing.contains(entry.fileId),
            onEvent = onEvent,
            onPlay = { onPlay(entry.toFilesItem()) },
            onShare = onShare?.let { share -> { share(entry.toFilesItem()) } },
            onDismiss = { sheetFileId = null },
        )
    }
    state.removal?.let { removal ->
        RemovalDialog(
            removal = removal,
            onConfirm = {
                selection.end()
                onEvent(DownloadsEvent.ConfirmRemoval)
            },
            onCancel = { onEvent(DownloadsEvent.CancelRemoval) },
        )
    }
}

private class DownloadSection(val key: String, val title: Int, val entries: List<DownloadEntry>)

/** What each row needs from the screen, so every section draws rows the same way. */
private class DownloadRows(
    val state: DownloadsState,
    /** Null outside selection. */
    val selection: Set<FilesItemId>?,
    val onEvent: (DownloadsEvent) -> Boolean,
    val onPlay: (FilesItem) -> Unit,
    val onOpenSheet: (DownloadEntry) -> Unit,
    val onToggle: (DownloadEntry) -> Unit,
)

private fun LazyListScope.section(section: DownloadSection, rows: DownloadRows, first: Boolean) {
    item(key = "header-${section.key}") {
        // In selection the tab title and its storage summary give way to the selection bar.
        val storage = rows.state.storageBytes.takeIf { section.key == "on-device" && rows.selection != null }
        MobileSectionLabel(
            text = stringResource(section.title),
            first = first,
            trailing = storage?.let { bytes ->
                {
                    Text(
                        Formatter.formatShortFileSize(LocalContext.current, bytes),
                        style = MaterialTheme.typography.bodySmall.putioMono(),
                    )
                }
            },
        )
    }
    items(section.entries, key = { "${section.key}-${it.fileId.value}" }) { entry ->
        val removing = entry.fileId in rows.state.removing
        MobileDownloadRow(
            entry = entry,
            meta = downloadMeta(entry, rows.state, removing),
            removing = removing,
            selected = rows.selection?.takeUnless { removing }?.contains(entry.fileId),
            onOpen = when {
                removing -> null
                rows.state.isAvailableOffline(entry.fileId) -> { { rows.onPlay(entry.toFilesItem()) } }
                else -> { { rows.onOpenSheet(entry) } }
            },
            onActions = { rows.onOpenSheet(entry) },
            onSelect = if (removing) null else { { rows.onToggle(entry) } },
            onEvent = rows.onEvent,
        )
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
    meta: AnnotatedString,
    removing: Boolean,
    /** Null outside selection; otherwise whether this row is selected. */
    selected: Boolean?,
    onOpen: (() -> Unit)?,
    onActions: () -> Unit,
    onSelect: (() -> Unit)?,
    onEvent: (DownloadsEvent) -> Boolean,
) {
    MobileRow(
        leading = {
            if (selected != null) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(uncheckedColor = PutioDesignTokens.solid),
                )
            } else {
                FileTypeIcon(entry.type, Modifier.size(FILE_ICON_SIZE))
            }
        },
        headline = { MobileMiddleCutText(entry.name) },
        meta = { Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailing = if (selected == null && !removing) {
            { DownloadRowTrailing(entry, onActions, onEvent) }
        } else {
            null
        },
        containerColor = if (selected == true) SELECTED_TINT else Color.Transparent,
        // A completed copy plays from its row, so the row is the player's starting bounds.
        modifier = Modifier.playbackContainer(entry.fileId.value, playable = entry.isCompleted)
            .downloadRowInteraction(
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

/** The ring while unfinished, Retry or Download again when stuck, and the actions on a local copy. */
@Composable
private fun DownloadRowTrailing(
    entry: DownloadEntry,
    onActions: () -> Unit,
    onEvent: (DownloadsEvent) -> Boolean,
) {
    val ring = entry.ringAction()
    when {
        ring != null -> DownloadRing(entry.status, ring) {
            onEvent(
                when (ring) {
                    DownloadRingAction.Pause -> DownloadsEvent.Pause(setOf(entry.fileId))
                    DownloadRingAction.Resume -> DownloadsEvent.Resume(setOf(entry.fileId))
                    DownloadRingAction.Cancel -> DownloadsEvent.Cancel(entry.fileId)
                },
            )
        }
        entry.canRetry -> IconButton(onClick = { onEvent(DownloadsEvent.Retry(entry.fileId)) }) {
            val missing = entry.status == DownloadStatus.Missing
            Icon(
                painterResource(if (missing) R.drawable.ic_ph_download_simple else R.drawable.ic_ph_arrow_clockwise),
                contentDescription = stringResource(
                    if (missing) R.string.mobile_downloads_download_again else R.string.mobile_downloads_retry,
                ),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        else -> IconButton(onClick = onActions) {
            Icon(
                painterResource(R.drawable.ic_ph_dots_three_vertical),
                contentDescription = stringResource(R.string.mobile_downloads_actions_named, entry.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

/**
 * One line of meta, its numbers in mono: progress and speed while running, the reason while
 * waiting or paused, place in line while queued, why it failed, or size and date on this device.
 */
@Composable
private fun downloadMeta(entry: DownloadEntry, state: DownloadsState, removing: Boolean): AnnotatedString {
    val context = LocalContext.current
    val resources = LocalResources.current
    val mono = MaterialTheme.typography.bodySmall.putioMono().toSpanStyle()
    val parts = DownloadMetaParts(context, mono)
    return buildAnnotatedString {
        if (removing) {
            append(resources.getString(R.string.mobile_downloads_status_removing))
            return@buildAnnotatedString
        }
        when (val status = entry.status) {
            DownloadStatus.Queued -> parts.queued(this, state.queuePosition(entry.fileId))
            is DownloadStatus.Downloading -> {
                parts.progress(this, status.bytesDownloaded, status.contentLength, status.percent)
                state.speeds[entry.fileId]?.takeIf { it > 0L }?.let { speed ->
                    append(SEPARATOR)
                    withStyle(mono) {
                        append(resources.getString(
                            R.string.mobile_transfer_speed,
                            Formatter.formatShortFileSize(context, speed),
                        ))
                    }
                }
            }
            is DownloadStatus.Paused -> {
                append(resources.getString(
                    when (status.reason) {
                        DownloadPauseReason.NETWORK -> R.string.mobile_files_download_waiting
                        DownloadPauseReason.STORAGE -> R.string.mobile_files_download_waiting_storage
                        DownloadPauseReason.USER -> R.string.mobile_downloads_meta_paused
                    },
                ))
                append(SEPARATOR)
                parts.progress(this, status.bytesDownloaded, status.contentLength, status.percent)
            }
            is DownloadStatus.Failed -> {
                withStyle(SpanStyle(color = PutioDesignTokens.redTextSecondary)) {
                    append(resources.getString(R.string.mobile_downloads_meta_failed))
                }
                append(SEPARATOR)
                append(resources.getString(status.reason.shortMessage()))
            }
            DownloadStatus.Missing -> append(resources.getString(R.string.mobile_files_download_missing))
            is DownloadStatus.Completed -> {
                withStyle(mono) { append(Formatter.formatShortFileSize(context, status.bytes)) }
                append(SEPARATOR)
                append(DateUtils.formatDateTime(
                    context,
                    entry.createdAt,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
                ))
            }
        }
    }
}

private class DownloadMetaParts(private val context: Context, private val mono: SpanStyle) {
    /** "Queued · #1 in line", its place in mono since it is a count; without a place, the wait alone. */
    fun queued(builder: AnnotatedString.Builder, place: Int?) {
        val text = place?.let { context.getString(R.string.mobile_downloads_status_queued_position, it) }
            ?: context.getString(R.string.mobile_downloads_status_queued)
        val number = place?.toString()
        val at = number?.let(text::indexOf) ?: -1
        if (number == null || at < 0) {
            builder.append(text)
        } else {
            builder.append(text.substring(0, at))
            builder.withStyle(mono) { append(number) }
            builder.append(text.substring(at + number.length))
        }
    }

    /** "1.4 GB of 3.3 GB" over a known size; else the bytes, with the percentage when Media3 has one. */
    fun progress(builder: AnnotatedString.Builder, bytes: Long, contentLength: Long?, percent: Float?) {
        val done = Formatter.formatShortFileSize(context, bytes)
        if (contentLength != null) {
            val template = context.getString(R.string.mobile_downloads_meta_of, VALUE_ONE, VALUE_TWO)
            builder.appendTemplate(template, done, Formatter.formatShortFileSize(context, contentLength))
        } else {
            builder.withStyle(mono) { append(done) }
            percent?.let {
                builder.append(SEPARATOR)
                builder.withStyle(mono) { append("${it.roundToInt().coerceIn(0, PERCENT.toInt())}%") }
            }
        }
    }

    private fun AnnotatedString.Builder.appendTemplate(template: String, first: String, second: String) {
        var rest = template
        while (rest.isNotEmpty()) {
            val one = rest.indexOf(VALUE_ONE)
            val two = rest.indexOf(VALUE_TWO)
            val next = listOf(one, two).filter { it >= 0 }.minOrNull()
            if (next == null) {
                append(rest)
                rest = ""
            } else {
                append(rest.substring(0, next))
                withStyle(mono) { append(if (next == one) first else second) }
                rest = rest.substring(next + VALUE_ONE.length)
            }
        }
    }
}

private fun DownloadFailureReason.shortMessage(): Int =
    when (this) {
        DownloadFailureReason.NETWORK -> R.string.mobile_downloads_meta_failed_network
        DownloadFailureReason.AUTHENTICATION -> R.string.mobile_downloads_meta_failed_authentication
        DownloadFailureReason.UNAVAILABLE -> R.string.mobile_downloads_meta_failed_unavailable
        DownloadFailureReason.STORAGE -> R.string.mobile_downloads_meta_failed_storage
        DownloadFailureReason.UNEXPECTED -> R.string.mobile_downloads_meta_failed_unexpected
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Text(stringResource(R.string.mobile_downloads_remove), color = PutioDesignTokens.redTextSecondary)
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
            PutioDestructiveButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(MOBILE_DOWNLOADS_REMOVE_CONFIRM_TAG),
            ) {
                Text(stringResource(R.string.mobile_downloads_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, colors = neutralTextButtonColors()) {
                Text(stringResource(R.string.mobile_action_cancel))
            }
        },
    )
}

/** The full sentence for a row's state, as the actions sheets show it. */
internal fun DownloadStatus.description(context: Context, queuePosition: Int? = null): String =
    when (this) {
        DownloadStatus.Queued -> queuePosition?.let {
            context.getString(R.string.mobile_downloads_status_queued_position, it)
        } ?: context.getString(R.string.mobile_downloads_status_queued)
        is DownloadStatus.Paused -> context.getString(
            when (reason) {
                DownloadPauseReason.NETWORK -> R.string.mobile_downloads_status_waiting_network
                DownloadPauseReason.STORAGE -> R.string.mobile_downloads_status_waiting_storage
                DownloadPauseReason.USER -> R.string.mobile_downloads_status_paused
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
private const val SEPARATOR = " · "
private const val VALUE_ONE = "\u0001"
private const val VALUE_TWO = "\u0002"
private val FILE_ICON_SIZE = 26.dp
private val SELECTED_TINT = PutioDesignTokens.yellowSolid.copy(alpha = 0.09f)
private val TOOLBAR_OFFSET = 24.dp
/** Space under the toolbar's own height, so the last row ends above it with a gap. */
private val TOOLBAR_CLEARANCE = TOOLBAR_OFFSET + 16.dp
