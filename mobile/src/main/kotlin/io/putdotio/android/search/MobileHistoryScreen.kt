package io.putdotio.android.search

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.putdotio.android.LoadNextPageNearEnd
import io.putdotio.android.MobileEmptyState
import io.putdotio.android.MobileErrorState
import io.putdotio.android.MobileLoadingState
import io.putdotio.android.MobileMiddleCutText
import io.putdotio.android.MobileRow
import io.putdotio.android.MobileSectionLabel
import io.putdotio.android.R
import io.putdotio.android.design.PutioDestructiveButton
import io.putdotio.android.design.putioMono
import io.putdotio.android.files.mobileMessage
import io.putdotio.android.history.HistoryClearing
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryNoticeType
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryState
import io.putdotio.android.parsePutioTimestamp
import io.putdotio.android.transfers.neutralTextButtonColors
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

internal const val MOBILE_HISTORY_LIST_TAG = "mobile-history-list"
internal const val MOBILE_HISTORY_OVERFLOW_TAG = "mobile-history-overflow"

/** The History tab. */
@Composable
internal fun MobileHistoryScreen(
    historyState: HistoryState,
    onHistoryEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = rememberToday(),
) {
    MobileHistoryContent(historyState, onHistoryEvent, today, modifier.fillMaxSize())
}

/** Today's date, moved on at midnight and on every resume, so Today and Yesterday stay true. */
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
    when (val content = state.content) {
        HistoryContent.Disabled ->
            MobileEmptyState(
                title = stringResource(R.string.mobile_history_disabled_title),
                message = stringResource(
                    R.string.mobile_history_disabled_message,
                    stringResource(R.string.mobile_settings_history),
                    stringResource(R.string.mobile_destination_account),
                ),
                modifier = modifier,
            )
        is HistoryContent.Loading ->
            MobileLoadingState(stringResource(R.string.mobile_history_loading), modifier)
        HistoryContent.Empty ->
            MobileEmptyState(
                title = stringResource(R.string.mobile_history_empty_title),
                message = stringResource(R.string.mobile_history_empty_message),
                modifier = modifier,
            )
        is HistoryContent.Failed ->
            MobileErrorState(
                title = stringResource(R.string.mobile_history_error_title),
                message = content.failure.mobileMessage(),
                retryLabel = stringResource(R.string.mobile_action_retry),
                onRetry = { onEvent(HistoryEvent.Retry) },
                modifier = modifier,
            )
        is HistoryContent.Ready ->
            MobileHistoryList(content, today, onEvent, modifier)
    }
    MobileHistoryDialog(state.clearing, onEvent)
}

@Composable
private fun MobileHistoryList(
    content: HistoryContent.Ready,
    today: LocalDate,
    onEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    // A stamp the parser cannot read keeps a group of its own, under itself.
    val grouped = remember(content.items, zone) {
        content.items.groupBy { item -> item.localDate(zone)?.let { it to null } ?: (null to item.createdAt) }
    }
    val listState = rememberLazyListState()
    LoadNextPageNearEnd(
        listState = listState,
        nextPage = content.paging as? HistoryPaging.Available,
        onLoadNextPage = { onEvent(HistoryEvent.LoadNextPage) },
    )
    LazyColumn(state = listState, modifier = modifier.testTag(MOBILE_HISTORY_LIST_TAG)) {
        var first = true
        grouped.forEach { (day, events) ->
            val (date, raw) = day
            val isFirst = first
            first = false
            item(key = date?.let { "date-$it" } ?: "raw-$raw") {
                MobileSectionLabel(historyDateLabel(date, today, raw.orEmpty()), first = isFirst)
            }
            items(events, key = { it.id.value }) { item -> MobileHistoryRow(item, onEvent) }
        }
        if (content.paging != HistoryPaging.Complete) {
            item(key = "history-paging") {
                MobileHistoryPaging(content.paging, onEvent, Modifier.fillMaxWidth())
            }
        }
    }
}

/** One event: a neutral glyph, the name it concerns cut in the middle, then what happened and when. */
@Composable
private fun MobileHistoryRow(item: HistoryItem, onEvent: (HistoryEvent) -> Unit) {
    val fileId = item.kind.navigableFileId()
    val mono = MaterialTheme.typography.bodySmall.putioMono().toSpanStyle()
    val time = item.timeLabel()
    val kind = item.kindLabel()
    MobileRow(
        leading = {
            Icon(
                painterResource(item.kind.glyph()),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(HISTORY_GLYPH_SIZE),
            )
        },
        headline = { MobileMiddleCutText(item.title()) },
        meta = {
            // The kind gives way at large font sizes; the time always shows.
            Row {
                kind?.let {
                    Text(
                        stringResource(R.string.mobile_history_metadata, it, ""),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(AnnotatedString(time, mono), maxLines = 1, softWrap = false)
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

/** The tab title's overflow: Clear history, which asks first. */
@Composable
internal fun MobileHistoryOverflow(clearing: HistoryClearing, onEvent: (HistoryEvent) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(MOBILE_HISTORY_OVERFLOW_TAG)) {
            Icon(
                painterResource(R.drawable.ic_ph_dots_three_vertical),
                contentDescription = stringResource(R.string.mobile_action_more_options),
            )
        }
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

private fun HistoryItem.localDate(zone: ZoneId): LocalDate? =
    parsePutioTimestamp(createdAt)?.atZone(zone)?.toLocalDate()

/** Today, Yesterday, then the date in words, with the year only when it is not this one. */
@Composable
private fun historyDateLabel(date: LocalDate?, today: LocalDate, raw: String): String {
    val context = LocalContext.current
    return when (date) {
        null -> raw
        today -> stringResource(R.string.mobile_history_today)
        today.minusDays(1) -> stringResource(R.string.mobile_history_yesterday)
        else -> DateUtils.formatDateTime(
            context,
            date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
                if (date.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR,
        )
    }
}

@Composable
private fun HistoryItem.timeLabel(): String {
    val context = LocalContext.current
    val created = parsePutioTimestamp(createdAt) ?: return createdAt
    return DateUtils.formatDateTime(context, created.toEpochMilli(), DateUtils.FORMAT_SHOW_TIME)
}

/** The name the event concerns; an event without one reads No title. */
@Composable
private fun HistoryItem.title(): String =
    when (val value = kind) {
        is HistoryEventKind.File -> value.name ?: stringResource(R.string.mobile_history_no_title)
        is HistoryEventKind.Transfer -> value.name ?: stringResource(R.string.mobile_history_no_title)
        is HistoryEventKind.Notice -> value.subject
        is HistoryEventKind.Other -> stringResource(R.string.mobile_history_no_title)
    }

/** What happened, before the time; an event of a type with no copy gives its time alone. */
@Composable
private fun HistoryItem.kindLabel(): String? =
    when (val value = kind) {
        is HistoryEventKind.File -> stringResource(R.string.mobile_history_kind_shared)
        is HistoryEventKind.Transfer -> stringResource(R.string.mobile_history_kind_transfer_completed)
        is HistoryEventKind.Notice -> stringResource(
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

/** Neutral glyphs: the event's kind, never the file's type. */
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

private fun HistoryEventKind.navigableFileId(): HistoryFileId? =
    when (this) {
        is HistoryEventKind.File -> id
        is HistoryEventKind.Transfer -> fileId
        is HistoryEventKind.Notice, is HistoryEventKind.Other -> null
    }

private val HISTORY_GLYPH_SIZE = 24.dp
/** Past midnight before the date is read again, so the new day has begun. */
private const val MIDNIGHT_SLACK_MILLIS = 1_000L
