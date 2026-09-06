package io.putdotio.android.trash

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

internal fun parseTrashTimestamp(value: String): Instant? = try {
    Instant.parse(value)
} catch (_: DateTimeParseException) {
    try {
        // The API's JSON encoder strips timezone information from UTC datetimes.
        LocalDateTime.parse(value).toInstant(ZoneOffset.UTC)
    } catch (_: DateTimeParseException) {
        null
    }
}
