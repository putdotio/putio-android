package io.putdotio.android.files

import android.content.Context
import android.text.format.DateUtils
import io.putdotio.android.parsePutioTimestamp
import java.util.Locale

/**
 * Decimal units as put.io's web app counts them: one decimal below 100 ("3.2GB", "19.4GB"), none
 * from 100 or when it is a zero ("31MB"). Android's short formatter drops the decimal from 10.
 * Mobile sets no space before the unit: in mono a space is a full cell, which sets the unit too far
 * off. TV's boards keep it ("31 MB"), so TV passes [spaced].
 */
public fun formatFileSize(bytes: Long, locale: Locale = Locale.getDefault(), spaced: Boolean = false): String {
    var value = bytes.coerceAtLeast(0L).toDouble()
    var unit = 0
    while (value >= UNIT_STEP && unit < SIZE_UNITS.lastIndex) {
        value /= UNIT_STEP
        unit++
    }
    val tenths = Math.round(value * TENTHS)
    val whole = unit == 0 || value >= ONE_DECIMAL_BELOW || tenths % TENTHS.toLong() == 0L
    val rounded = String.format(locale, if (whole) "%.0f" else "%.1f", if (whole) value else tenths / TENTHS)
    return if (spaced) "$rounded ${SIZE_UNITS[unit]}" else "$rounded${SIZE_UNITS[unit]}"
}

/** "Oct 13" this year, "Oct 13, 2024" before it. */
public fun String.toDisplayDate(context: Context): String? =
    parsePutioTimestamp(this)
        ?.toEpochMilli()
        ?.let { timestamp ->
            DateUtils.formatDateTime(context, timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        }

public fun String.toFullDate(context: Context): String? =
    parsePutioTimestamp(this)?.toEpochMilli()?.let { timestamp ->
        DateUtils.formatDateTime(
            context,
            timestamp,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }

/** A folder's last change, "3 days ago"; null for a file, or a folder put.io sent no time for. */
public fun FilesItem.updatedAgo(nowMillis: Long): String? = updatedAt?.takeIf { isFolder }?.toAgo(nowMillis)

/** "3 hr. ago" from a put.io timestamp, "Oct 7" after a week; a time ahead of now reads as now. */
public fun String.toAgo(nowMillis: Long): String? =
    parsePutioTimestamp(this)?.toEpochMilli()?.let { time ->
        DateUtils.getRelativeTimeSpanString(
            time.coerceAtMost(nowMillis),
            nowMillis,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE or DateUtils.FORMAT_ABBREV_MONTH,
        ).toString()
    }

private val SIZE_UNITS = listOf("B", "kB", "MB", "GB", "TB", "PB")
private const val UNIT_STEP = 1_000.0
private const val ONE_DECIMAL_BELOW = 100.0
private const val TENTHS = 10.0
