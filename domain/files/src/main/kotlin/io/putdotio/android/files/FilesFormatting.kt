package io.putdotio.android.files

import android.content.Context
import android.text.format.DateUtils
import io.putdotio.android.parsePutioTimestamp
import java.util.Locale

public fun formatFileSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    var value = bytes.coerceAtLeast(0L).toDouble()
    var unit = 0
    while (value >= UNIT_STEP && unit < SIZE_UNITS.lastIndex) {
        value /= UNIT_STEP
        unit++
    }
    val tenths = Math.round(value * TENTHS)
    val whole = unit == 0 || value >= ONE_DECIMAL_BELOW || tenths % TENTHS.toLong() == 0L
    val rounded = String.format(locale, if (whole) "%.0f" else "%.1f", if (whole) value else tenths / TENTHS)
    return "$rounded ${SIZE_UNITS[unit]}"
}

public fun String.toDisplayDate(context: Context): String? =
    parsePutioTimestamp(this)?.toEpochMilli()?.toDisplayDate(context)

public fun Long.toDisplayDate(context: Context): String =
    DateUtils.formatDateTime(context, this, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

public fun String.toFullDate(context: Context): String? =
    parsePutioTimestamp(this)?.toEpochMilli()?.let { timestamp ->
        DateUtils.formatDateTime(
            context,
            timestamp,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }

public fun FilesItem.updatedAgo(nowMillis: Long): String? = updatedAt?.takeIf { isFolder }?.toAgo(nowMillis)

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

public fun String.nameStemEnd(isFolder: Boolean): Int {
    val extension = lastIndexOf('.')
    return if (isFolder || extension <= 0) length else extension
}
