package io.putdotio.android.downloads

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.putdotio.android.MobileSelectedRowTint
import io.putdotio.android.MobileSelectionCheckbox
import io.putdotio.android.MobileSelectionToolbarClearance
import io.putdotio.android.MobileSelectionToolbarOffset
import io.putdotio.android.R
import io.putdotio.android.appendMonoFigures
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.files.MobileFilesActionRow
import io.putdotio.android.files.MobileFilesActionTiles
import io.putdotio.android.files.MobileFilesActionsSheet
import io.putdotio.android.files.MobileFilesDestructiveAction
import io.putdotio.android.files.MobileFilesSheetHeader
import io.putdotio.android.files.MobileFilesSheetMeta
import io.putdotio.android.files.toDisplayDate
import io.putdotio.android.monoFigures
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
import io.putdotio.android.MobileIconButton
import androidx.compose.material3.IconButtonDefaults

internal const val MOBILE_DOWNLOADS_ROUTE = "downloads"
internal const val MOBILE_DOWNLOADS_LIST_TAG = "mobile-downloads-list"
internal const val MOBILE_DOWNLOADS_ITEM_SHEET_TAG = "mobile-downloads-item-sheet"
internal const val MOBILE_DOWNLOADS_RETRY_ACTION_TAG = "mobile-downloads-retry-action"
internal const val MOBILE_DOWNLOADS_REMOVE_ACTION_TAG = "mobile-downloads-remove-action"
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
    val toolbarClearance = with(LocalDensity.current) { toolbarHeight.toDp() } + MobileSelectionToolbarClearance
    val selected = selection.selected(state)
    LifecycleStartEffect(onEvent) {
        onEvent(DownloadsEvent.Shown)
        onStopOrDispose { onEvent(DownloadsEvent.Hidden) }
    }
    LaunchedEffect(selection, state.entries, state.removing) { selection.reconcile(state) }
    LaunchedEffect(sheetFileId, sheetEntry == null) {
        if (sheetEntry == null) sheetFileId = null
    }
    LaunchedEffect(state.focus) {
        val focus = state.focus ?: return@LaunchedEffect
        if (state.entry(focus) != null) {
            selection.end()
            sheetFileId = focus.value
        }
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
                    .padding(bottom = MobileSelectionToolbarOffset)
                    .onSizeChanged { toolbarHeight = it.height },
            )
        }
    }
    sheetEntry?.takeIf { !inSelection }?.let { entry ->
        MobileDownloadItemSheet(
            entry = entry,
            removing = state.removing.contains(entry.fileId),
            queuePosition = state.queuePosition(entry.fileId),
            onEvent = onEvent,
            onPlay = onPlay,
            onShare = onShare,
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
    item(key = "header-${section.key}", contentType = "header") {
        // In selection the tab title and its storage summary give way to the selection bar.
        val storage = rows.state.storageBytes.takeIf { section.key == "on-device" && rows.selection != null }
        MobileSectionLabel(
            text = stringResource(section.title),
            first = first,
            trailing = storage?.let { bytes ->
                {
                    val style = MaterialTheme.typography.bodySmall
                    Text(monoFigures(formatFileSize(bytes), style.putioMono().toSpanStyle()), style = style)
                }
            },
        )
    }
    items(section.entries, key = { "${section.key}-${it.fileId.value}" }, contentType = { "row" }) { entry ->
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
                MobileSelectionCheckbox(checked = selected)
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
        containerColor = if (selected == true) MobileSelectedRowTint else Color.Transparent,
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
        entry.canRetry -> MobileIconButton(
            stringResource(entry.retryLabel(R.string.mobile_downloads_retry)),
            entry.retryIcon(),
            onClick = { onEvent(DownloadsEvent.Retry(entry.fileId)) },
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        )
        else -> MobileIconButton(
            stringResource(R.string.mobile_downloads_actions_named, entry.name),
            R.drawable.ic_ph_dots_three_vertical,
            onActions,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        )
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
                    appendMonoFigures(resources.getString(R.string.mobile_transfer_speed, formatFileSize(speed)), mono)
                }
            }
            is DownloadStatus.Paused -> {
                append(resources.getString(status.reason.label))
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
                appendMonoFigures(formatFileSize(status.bytes), mono)
                append(SEPARATOR)
                append(entry.createdAt.toDisplayDate(context))
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

    /**
     * "1.4 GB of 3.3 GB" over a known size. An HLS rendition counts its percentage in segments,
     * not bytes, so it shows the percentage alone, as its ring does; with neither, the bytes.
     */
    fun progress(builder: AnnotatedString.Builder, bytes: Long, contentLength: Long?, percent: Float?) {
        val done = formatFileSize(bytes)
        when {
            contentLength != null -> {
                val template = context.getString(R.string.mobile_downloads_meta_of, VALUE_ONE, VALUE_TWO)
                builder.appendTemplate(template, done, formatFileSize(contentLength))
            }
            percent != null ->
                builder.withStyle(mono) { append("${percent.roundToInt().coerceIn(0, PERCENT.toInt())}%") }
            else -> builder.appendMonoFigures(done, mono)
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
                appendMonoFigures(if (next == one) first else second, mono)
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

@Composable
private fun MobileDownloadItemSheet(
    entry: DownloadEntry,
    removing: Boolean,
    queuePosition: Int?,
    onEvent: (DownloadsEvent) -> Boolean,
    onPlay: (FilesItem) -> Unit,
    onShare: ((FilesItem) -> Unit)?,
    onDismiss: () -> Unit,
) {
    MobileFilesActionsSheet(
        onDismiss = onDismiss,
        header = {
            MobileFilesSheetHeader(entry.type, entry.name) { DownloadSheetMeta(entry, removing, queuePosition) }
        },
        tiles = {
            MobileFilesActionTiles(
                item = entry.toFilesItem(),
                resumes = false,
                downloadStatus = null,
                onPlayItem = onPlay.takeIf { entry.isCompleted && !removing },
                onOpenItem = null,
                onDownloadItem = null,
                onShareItem = onShare?.takeIf { !removing },
                onDismiss = onDismiss,
            )
        },
        modifier = Modifier.testTag(MOBILE_DOWNLOADS_ITEM_SHEET_TAG),
    ) {
        if (entry.canRetry && !removing) {
            MobileFilesActionRow(
                icon = entry.retryIcon(),
                label = entry.retryLabel(R.string.mobile_action_retry),
                tag = MOBILE_DOWNLOADS_RETRY_ACTION_TAG,
            ) {
                onDismiss()
                onEvent(DownloadsEvent.Retry(entry.fileId))
            }
        }
        MobileFilesDestructiveAction(
            icon = R.drawable.ic_ph_device_mobile_slash,
            label = R.string.mobile_downloads_remove,
            tag = MOBILE_DOWNLOADS_REMOVE_ACTION_TAG,
            enabled = !removing,
        ) {
            onDismiss()
            onEvent(DownloadsEvent.RequestRemoval(setOf(entry.fileId)))
        }
    }
}

@Composable
private fun DownloadSheetMeta(entry: DownloadEntry, removing: Boolean, queuePosition: Int?) {
    val context = LocalContext.current
    val status = entry.status
    when {
        removing -> MobileFilesSheetMeta(null, stringResource(R.string.mobile_downloads_status_removing))
        status is DownloadStatus.Completed ->
            MobileFilesSheetMeta(formatFileSize(status.bytes), entry.createdAt.toDisplayDate(context))
        else ->
            MobileFilesSheetMeta(null, status.description(context, queuePosition), detailMaxLines = Int.MAX_VALUE)
    }
}

private fun DownloadEntry.retryIcon(): Int =
    if (status == DownloadStatus.Missing) R.drawable.ic_ph_download_simple else R.drawable.ic_ph_arrow_clockwise

private fun DownloadEntry.retryLabel(retry: Int): Int =
    if (status == DownloadStatus.Missing) R.string.mobile_downloads_download_again else retry

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
            formatFileSize(bytesDownloaded),
        )
        is DownloadStatus.Downloading -> percent?.let {
            val rounded = it.roundToInt().coerceIn(0, PERCENT.toInt())
            context.getString(R.string.mobile_downloads_status_downloading, rounded)
        } ?: context.getString(
            R.string.mobile_downloads_status_downloading_unknown,
            formatFileSize(bytesDownloaded),
        )
        is DownloadStatus.Completed ->
            context.getString(R.string.mobile_downloads_status_completed, formatFileSize(bytes))
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

internal val DownloadPauseReason.label: Int
    @StringRes get() = when (this) {
        DownloadPauseReason.NETWORK -> R.string.mobile_files_download_waiting
        DownloadPauseReason.STORAGE -> R.string.mobile_files_download_waiting_storage
        DownloadPauseReason.USER -> R.string.mobile_downloads_meta_paused
    }
