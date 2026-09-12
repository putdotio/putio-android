package io.putdotio.android.tv.history

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.putdotio.android.R
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** The oracle's date groups (08): a header per bucket, newest first. */
internal enum class TvHistoryBucket(@StringRes val label: Int) {
    Today(R.string.tv_history_today),
    Yesterday(R.string.tv_history_yesterday),
    LastWeek(R.string.tv_history_last_week),
    LastMonth(R.string.tv_history_last_month),
    Earlier(R.string.tv_history_earlier),
}

/** put.io stamps events in UTC without an offset; an offset is accepted when present. */
internal fun HistoryItem.createdInstant(): Instant? =
    try {
        Instant.parse(createdAt)
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(createdAt).toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }

internal fun HistoryItem.bucket(now: Instant, zone: ZoneId): TvHistoryBucket {
    val created = createdInstant() ?: return TvHistoryBucket.Earlier
    val today = now.atZone(zone).toLocalDate()
    val day = created.atZone(zone).toLocalDate()
    val daysAgo = ChronoUnit.DAYS.between(day, today)
    return when {
        daysAgo <= 0L -> TvHistoryBucket.Today
        daysAgo == 1L -> TvHistoryBucket.Yesterday
        daysAgo < DAYS_IN_WEEK -> TvHistoryBucket.LastWeek
        daysAgo < DAYS_IN_MONTH -> TvHistoryBucket.LastMonth
        else -> TvHistoryBucket.Earlier
    }
}

/** "2 days ago" like the oracle; the raw stamp when it cannot be parsed. */
internal fun HistoryItem.relativeTime(now: Instant): CharSequence {
    val created = createdInstant() ?: return createdAt
    return DateUtils.getRelativeTimeSpanString(created.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS)
}

@StringRes
internal fun HistoryEventKind.tvLabel(): Int =
    when (this) {
        is HistoryEventKind.File -> R.string.tv_history_shared_file
        is HistoryEventKind.Transfer -> R.string.tv_history_completed_transfer
        is HistoryEventKind.Other -> R.string.tv_history_activity
    }

@DrawableRes
internal fun HistoryEventKind.tvIcon(): Int =
    when (this) {
        is HistoryEventKind.File -> R.drawable.ic_ph_file_fill
        is HistoryEventKind.Transfer -> R.drawable.ic_ph_arrow_circle_down_fill
        is HistoryEventKind.Other -> R.drawable.ic_ph_clock_counter_clockwise
    }

internal fun HistoryEventKind.navigableFileId(): HistoryFileId? =
    when (this) {
        is HistoryEventKind.File -> id
        is HistoryEventKind.Transfer -> fileId
        is HistoryEventKind.Other -> null
    }

private const val DAYS_IN_WEEK = 7L
private const val DAYS_IN_MONTH = 30L
