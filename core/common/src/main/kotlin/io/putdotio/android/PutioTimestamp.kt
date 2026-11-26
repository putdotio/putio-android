package io.putdotio.android

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField

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
