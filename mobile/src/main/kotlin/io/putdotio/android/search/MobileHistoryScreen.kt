package io.putdotio.android.search

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.putdotio.android.LoadNextPageNearEnd
import io.putdotio.android.MobileEmptyState
import io.putdotio.android.MobileErrorState
import io.putdotio.android.MobileLoadingState
import io.putdotio.android.MobileMiddleCutText
import io.putdotio.android.MobilePullToRefresh
import io.putdotio.android.MobileRow
import io.putdotio.android.MobileSectionLabel
import io.putdotio.android.R
import io.putdotio.android.design.PutioDestructiveButton
import io.putdotio.android.design.Built
import io.putdotio.android.design.buildReusing
import io.putdotio.android.design.rememberBuiltOffMain
import io.putdotio.android.design.putioMono
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.files.mobileMessage
import io.putdotio.android.history.HistoryClearing
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryNoticeType
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryState
import io.putdotio.android.history.navigableFileId
import io.putdotio.android.history.sizeBytes
import io.putdotio.android.parsePutioTimestamp
import io.putdotio.android.settings.HistoryOpen
import io.putdotio.android.neutralTextButtonColors
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay
import io.putdotio.android.MobileIconButton

internal const val MOBILE_HISTORY_LIST_TAG = "mobile-history-list"
internal const val MOBILE_HISTORY_OVERFLOW_TAG = "mobile-history-overflow"

@Composable
internal fun MobileHistoryScreen(
    historyState: HistoryState,
    onHistoryEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier,
    open: HistoryOpen = HistoryOpen.History,
    onRetryOpen: () -> Unit = {},
    today: LocalDate = rememberToday(),
) {
    LaunchedEffect(Unit) { onHistoryEvent(HistoryEvent.Refresh) }
    when (open) {
        HistoryOpen.History -> MobileHistoryContent(historyState, onHistoryEvent, today, modifier.fillMaxSize())
        is HistoryOpen.Failed ->
            MobileErrorState(
                title = stringResource(R.string.mobile_history_error_title),
                message = open.failure.mobileMessage(),
                retryLabel = stringResource(R.string.mobile_action_retry),
                onRetry = onRetryOpen,
                modifier = modifier.fillMaxSize(),
            )
        HistoryOpen.Pending, HistoryOpen.FilesRoot ->
            MobileLoadingState(stringResource(R.string.mobile_history_loading), modifier.fillMaxSize())
    }
}

@Composable
private fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleResumeEffect(Unit) {
        today = LocalDate.now()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        while (true) {
            val midnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
            delay((midnight.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L) + MIDNIGHT_SLACK_MILLIS)
            today = LocalDate.now()
        }
    }
    return today
}

@Composable
private fun MobileHistoryContent(
    state: HistoryState,
    onEvent: (HistoryEvent) -> Unit,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.isRefreshing) { if (!state.isRefreshing) pulled = false }
    val refreshing = pulled && state.isRefreshing
    val reload = {
        pulled = true
        onEvent(HistoryEvent.Reload)
    }
    when (val content = state.content) {
        HistoryContent.Disabled, is HistoryContent.Loading ->
            MobileLoadingState(stringResource(R.string.mobile_history_loading), modifier)
        HistoryContent.Empty ->
            MobilePullToRefresh(refreshing, state.clearing == HistoryClearing.Idle, reload, modifier) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val viewportHeight = maxHeight
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        MobileEmptyState(
                            title = stringResource(R.string.mobile_history_empty_title),
                            message = stringResource(R.string.mobile_history_empty_message),
                            modifier = Modifier.fillMaxWidth().height(viewportHeight),
                        )
                    }
                }
            }
        is HistoryContent.Failed ->
            MobileErrorState(
                title = stringResource(R.string.mobile_history_error_title),
                message = content.failure.mobileMessage(),
                retryLabel = stringResource(R.string.mobile_action_retry),
                onRetry = { onEvent(HistoryEvent.Retry) },
                modifier = modifier,
            )
        is HistoryContent.Ready ->
            MobilePullToRefresh(refreshing, state.clearing == HistoryClearing.Idle, reload, modifier) {
                MobileHistoryList(content, today, onEvent, Modifier.fillMaxSize())
            }
    }
    MobileHistoryDialog(state.clearing, onEvent)
}

@Composable
@NonSkippableComposable
private fun MobileHistoryList(
    content: HistoryContent.Ready,
    today: LocalDate,
    onEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val history = rememberHistoryDays(content.items, today) ?: return
    LoadNextPageNearEnd(
        listState = listState,
        nextPage = (content.paging as? HistoryPaging.Available).takeIf { history.isBuiltFrom(content.items) },
        onLoadNextPage = { onEvent(HistoryEvent.LoadNextPage) },
    )
    LazyColumn(state = listState, modifier = modifier.testTag(MOBILE_HISTORY_LIST_TAG)) {
        history.value.days.forEachIndexed { index, day ->
            item(key = day.key, contentType = "day") {
                MobileSectionLabel(day.label, first = index == 0)
            }
            items(day.events, key = { it.item.id.value }, contentType = { "row" }) { row ->
                MobileHistoryRow(row.item, row.text, onEvent)
            }
        }
        if (content.paging != HistoryPaging.Complete) {
            item(key = "history-paging", contentType = "paging") {
                MobileHistoryPaging(content.paging, onEvent, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MobileHistoryRow(item: HistoryItem, text: HistoryRowText, onEvent: (HistoryEvent) -> Unit) {
    val fileId = item.kind.navigableFileId()
    val mono = MaterialTheme.typography.bodySmall.putioMono().toSpanStyle()
    val kind = text.kind
    val size = text.size
    MobileRow(
        leading = {
            Icon(
                painterResource(item.kind.glyph()),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(HISTORY_GLYPH_SIZE),
            )
        },
        headline = { MobileMiddleCutText(item.title(LocalContext.current)) },
        meta = {
            Row {
                kind?.let {
                    Text(
                        it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    HistoryMetaSeparator()
                }
                if (size != null) {
                    Text(AnnotatedString(size, mono), maxLines = 1, softWrap = false)
                    HistoryMetaSeparator()
                }
                Text(AnnotatedString(text.time, mono), maxLines = 1, softWrap = false)
            }
        },
        modifier = if (fileId == null) Modifier else Modifier.clickable(
            role = Role.Button,
            onClickLabel = stringResource(R.string.mobile_history_open_file),
        ) {
            onEvent(HistoryEvent.OpenFile(fileId))
        },
    )
}

@Composable
private fun HistoryMetaSeparator() {
    Text(
        HISTORY_META_SEPARATOR,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.clearAndSetSemantics {},
    )
}

@Composable
internal fun MobileHistoryOverflow(clearing: HistoryClearing, onEvent: (HistoryEvent) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        MobileIconButton(
            stringResource(R.string.mobile_action_more_options),
            R.drawable.ic_ph_dots_three_vertical,
            onClick = { open = true },
            modifier = Modifier.testTag(MOBILE_HISTORY_OVERFLOW_TAG),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_history_clear)) },
                onClick = {
                    open = false
                    onEvent(HistoryEvent.RequestClear)
                },
                enabled = clearing == HistoryClearing.Idle,
            )
        }
    }
}

@Composable
private fun MobileHistoryPaging(
    paging: HistoryPaging,
    onEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (paging) {
        is HistoryPaging.Available ->
            TextButton(onClick = { onEvent(HistoryEvent.LoadNextPage) }, modifier = modifier) {
                Text(stringResource(R.string.mobile_history_load_more))
            }
        is HistoryPaging.Loading ->
            Row(
                modifier = modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.mobile_history_loading_more))
            }
        is HistoryPaging.Failed ->
            Column(modifier = modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.mobile_history_paging_error))
                TextButton(onClick = { onEvent(HistoryEvent.Retry) }) {
                    Text(stringResource(R.string.mobile_action_retry))
                }
            }
        HistoryPaging.Complete -> Unit
    }
}

@Composable
private fun MobileHistoryDialog(
    clearing: HistoryClearing,
    onEvent: (HistoryEvent) -> Unit,
) {
    when (clearing) {
        HistoryClearing.AwaitingConfirmation ->
            AlertDialog(
                onDismissRequest = { onEvent(HistoryEvent.DismissClear) },
                title = { Text(stringResource(R.string.mobile_history_clear_title)) },
                text = { Text(stringResource(R.string.mobile_history_clear_message)) },
                confirmButton = {
                    PutioDestructiveButton(onClick = { onEvent(HistoryEvent.ConfirmClear) }) {
                        Text(stringResource(R.string.mobile_history_clear_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onEvent(HistoryEvent.DismissClear) }, colors = neutralTextButtonColors()) {
                        Text(stringResource(R.string.mobile_action_cancel))
                    }
                },
            )
        is HistoryClearing.Clearing ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.mobile_history_clearing)) },
                text = { CircularProgressIndicator() },
                confirmButton = {},
            )
        is HistoryClearing.Failed ->
            AlertDialog(
                onDismissRequest = { onEvent(HistoryEvent.DismissClear) },
                title = { Text(stringResource(R.string.mobile_history_clear_error_title)) },
                text = { Text(clearing.failure.mobileMessage()) },
                confirmButton = {
                    TextButton(onClick = { onEvent(HistoryEvent.DismissClear) }) {
                        Text(stringResource(R.string.mobile_action_ok))
                    }
                },
            )
        HistoryClearing.Idle -> Unit
    }
}

private sealed interface HistoryDayKey {
    data class Date(val date: LocalDate) : HistoryDayKey

    data class Raw(val createdAt: String) : HistoryDayKey
}

private class HistoryRow(val item: HistoryItem, val day: HistoryDayKey, val text: HistoryRowText)

private class HistoryDay(val key: String, val label: String, val events: List<HistoryRow>)

private class HistoryDays(val days: List<HistoryDay>, val rows: Map<HistoryItem, HistoryRow>)

@Composable
private fun rememberHistoryDays(
    items: List<HistoryItem>,
    today: LocalDate,
): Built<List<HistoryItem>, HistoryDays>? {
    val context = LocalContext.current.applicationContext
    val locales = LocalConfiguration.current.locales
    val locale = locales[0]
    val hours24 = DateFormat.is24HourFormat(context)
    val zone = ZoneId.systemDefault()
    return rememberBuiltOffMain(items, today, zone, context, locales, hours24) { next, previous ->
        next.historyDays(context, today, zone, locale, previous?.rows.orEmpty())
    }
}

private suspend fun List<HistoryItem>.historyDays(
    context: Context,
    today: LocalDate,
    zone: ZoneId,
    locale: Locale,
    built: Map<HistoryItem, HistoryRow>,
): HistoryDays {
    val rows = buildReusing(built) { HistoryRow(it, it.day(zone), it.rowText(context, locale)) }
    val days = rows.rows.groupBy { it.day }.map { (day, events) ->
        when (day) {
            is HistoryDayKey.Date -> HistoryDay("date-${day.date}", day.date.label(context, today, zone), events)
            is HistoryDayKey.Raw -> HistoryDay("raw-${day.createdAt}", day.createdAt, events)
        }
    }
    return HistoryDays(days, rows.byItem)
}

private fun HistoryItem.day(zone: ZoneId): HistoryDayKey =
    parsePutioTimestamp(createdAt)?.atZone(zone)?.toLocalDate()?.let { HistoryDayKey.Date(it) }
        ?: HistoryDayKey.Raw(createdAt)

private fun LocalDate.label(context: Context, today: LocalDate, zone: ZoneId): String =
    when (this) {
        today -> context.getString(R.string.mobile_history_today)
        today.minusDays(1) -> context.getString(R.string.mobile_history_yesterday)
        else -> DateUtils.formatDateTime(
            context,
            atStartOfDay(zone).toInstant().toEpochMilli(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
                if (year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR,
        )
    }

private data class HistoryRowText(val kind: String?, val size: String?, val time: String)

private fun HistoryItem.rowText(context: Context, locale: Locale): HistoryRowText =
    HistoryRowText(
        kind = kindLabel(context),
        size = kind.sizeBytes()?.let { formatFileSize(it, locale) },
        time = timeLabel(context),
    )

private fun HistoryItem.timeLabel(context: Context): String {
    val created = parsePutioTimestamp(createdAt) ?: return createdAt
    return DateUtils.formatDateTime(context, created.toEpochMilli(), DateUtils.FORMAT_SHOW_TIME)
}

private fun HistoryItem.title(context: Context): String =
    when (val value = kind) {
        is HistoryEventKind.File -> value.name ?: context.getString(R.string.mobile_history_no_title)
        is HistoryEventKind.Transfer -> value.name ?: context.getString(R.string.mobile_history_no_title)
        is HistoryEventKind.Notice -> value.subject
        is HistoryEventKind.Other -> context.getString(R.string.mobile_history_no_title)
    }

private fun HistoryItem.kindLabel(context: Context): String? =
    when (val value = kind) {
        is HistoryEventKind.File -> value.sharedBy?.takeIf(String::isNotBlank)
            ?.let { context.getString(R.string.mobile_history_shared_by, it) }
            ?: context.getString(R.string.mobile_history_kind_shared)
        is HistoryEventKind.Transfer -> context.getString(R.string.mobile_history_kind_transfer_completed)
        is HistoryEventKind.Notice -> context.getString(
            when (value.type) {
                HistoryNoticeType.Upload -> R.string.mobile_history_kind_uploaded
                HistoryNoticeType.TransferError -> R.string.mobile_history_kind_transfer_failed
                HistoryNoticeType.RssFileDeleted -> R.string.mobile_history_kind_rss_file_deleted
                HistoryNoticeType.RssFilterPaused -> R.string.mobile_history_kind_rss_filter_paused
                HistoryNoticeType.RssTransferError -> R.string.mobile_history_kind_rss_transfer_failed
                HistoryNoticeType.TransferCallbackError -> R.string.mobile_history_kind_callback_failed
            },
        )
        is HistoryEventKind.Other -> null
    }

@DrawableRes
private fun HistoryEventKind.glyph(): Int =
    when (this) {
        is HistoryEventKind.File -> R.drawable.ic_ph_share_network
        is HistoryEventKind.Transfer -> R.drawable.ic_ph_check_circle
        is HistoryEventKind.Notice -> when (type) {
            HistoryNoticeType.Upload -> R.drawable.ic_ph_upload_simple
            HistoryNoticeType.RssFileDeleted -> R.drawable.ic_ph_trash
            HistoryNoticeType.RssFilterPaused -> R.drawable.ic_ph_pause_circle
            HistoryNoticeType.TransferError,
            HistoryNoticeType.RssTransferError,
            HistoryNoticeType.TransferCallbackError,
            -> R.drawable.ic_ph_warning_circle
        }
        is HistoryEventKind.Other -> R.drawable.ic_ph_clock_counter_clockwise
    }

private const val HISTORY_META_SEPARATOR = " · "
private val HISTORY_GLYPH_SIZE = 24.dp
private const val MIDNIGHT_SLACK_MILLIS = 1_000L
