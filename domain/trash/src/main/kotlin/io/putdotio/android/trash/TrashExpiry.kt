package io.putdotio.android.trash

import io.putdotio.android.parsePutioTimestamp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

public fun String.trashDaysLeft(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val date = parsePutioTimestamp(this)?.atZone(zone)?.toLocalDate() ?: try {
        LocalDate.parse(this)
    } catch (_: DateTimeParseException) {
        return null
    }
    return ChronoUnit.DAYS.between(today, date).coerceAtLeast(0L)
}
