package io.putdotio.android

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField

/**
 * A put.io API timestamp, or null when it is not one. The API's JSON encoder strips the
 * zone from UTC datetimes (`2026-09-09T15:25:32`); an explicit offset is honoured when present.
 */
public fun parsePutioTimestamp(value: String): Instant? {
    val parsed = try {
        DateTimeFormatter.ISO_DATE_TIME.parse(value)
    } catch (_: DateTimeParseException) {
        return null
    }
    return if (parsed.isSupported(ChronoField.OFFSET_SECONDS)) {
        Instant.from(parsed)
    } else {
        LocalDateTime.from(parsed).toInstant(ZoneOffset.UTC)
    }
}
