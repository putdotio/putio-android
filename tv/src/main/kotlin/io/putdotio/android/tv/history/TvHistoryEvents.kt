package io.putdotio.android.tv.history

import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import io.putdotio.android.R
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryNoticeType
import io.putdotio.android.parsePutioTimestamp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun HistoryItem.localDate(zone: ZoneId): LocalDate? =
    parsePutioTimestamp(createdAt)?.atZone(zone)?.toLocalDate()

internal data class TvHistoryDay(val date: LocalDate?, val raw: String?)

internal fun HistoryItem.day(zone: ZoneId): TvHistoryDay =
    localDate(zone)?.let { TvHistoryDay(it, null) } ?: TvHistoryDay(null, createdAt)

@Composable
@ReadOnlyComposable
internal fun TvHistoryDay.label(today: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (date) {
        null -> raw.orEmpty()
        today -> stringResource(R.string.tv_history_today)
        today.minusDays(1) -> stringResource(R.string.tv_history_yesterday)
        else -> {
            val skeleton = if (date.year == today.year) "MMMd" else "MMMdyyyy"
            date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun HistoryItem.timeLabel(zone: ZoneId): String =
    parsePutioTimestamp(createdAt)?.atZone(zone)?.format(TIME_FORMAT) ?: createdAt

@Composable
@ReadOnlyComposable
internal fun HistoryItem.title(): String =
    when (val value = kind) {
        is HistoryEventKind.File -> value.name
        is HistoryEventKind.Transfer -> value.name
        is HistoryEventKind.Notice -> value.subject
        is HistoryEventKind.Other -> null
    } ?: stringResource(R.string.tv_history_no_title)

@StringRes
internal fun HistoryEventKind.kindLabel(): Int? =
    when (this) {
        is HistoryEventKind.File -> R.string.tv_history_kind_shared
        is HistoryEventKind.Transfer -> R.string.tv_history_kind_transfer_completed
        is HistoryEventKind.Notice -> when (type) {
            HistoryNoticeType.Upload -> R.string.tv_history_kind_uploaded
            HistoryNoticeType.TransferError -> R.string.tv_history_kind_transfer_failed
            HistoryNoticeType.RssFileDeleted -> R.string.tv_history_kind_rss_file_deleted
            HistoryNoticeType.RssFilterPaused -> R.string.tv_history_kind_rss_filter_paused
            HistoryNoticeType.RssTransferError -> R.string.tv_history_kind_rss_transfer_failed
            HistoryNoticeType.TransferCallbackError -> R.string.tv_history_kind_callback_failed
        }
        is HistoryEventKind.Other -> null
    }

@DrawableRes
internal fun HistoryEventKind.glyph(): Int =
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

internal fun HistoryEventKind.navigableFileId(): HistoryFileId? =
    when (this) {
        is HistoryEventKind.File -> id
        is HistoryEventKind.Transfer -> fileId
        is HistoryEventKind.Notice, is HistoryEventKind.Other -> null
    }
