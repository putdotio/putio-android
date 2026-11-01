package io.putdotio.android.trash

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashExpiryTest {
    private val today = LocalDate.of(2026, 9, 6)
    private val utc = ZoneId.of("UTC")

    @Test
    fun missingAndInvalidExpirationNeverProduceADeadline() {
        assertNull("not-a-date".trashDaysLeft(today))
        assertNull("2026-99-99".trashDaysLeft(today))
    }

    @Test
    fun backendNaiveUtcDatesCountTheSameDaysAsExplicitUtc() {
        val expires = "2026-09-20T10:00:00"
        assertEquals(14L, expires.trashDaysLeft(today, utc))
        assertEquals("${expires}Z".trashDaysLeft(today, utc), expires.trashDaysLeft(today, utc))
    }

    @Test
    fun aPassedDateCountsAsTheLastDay() {
        assertEquals(0L, "2026-09-01".trashDaysLeft(today, utc))
    }
}
