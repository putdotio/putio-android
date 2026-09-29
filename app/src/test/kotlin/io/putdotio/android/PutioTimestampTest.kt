package io.putdotio.android

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PutioTimestampTest {
    @Test
    fun `a zone-less stamp is read as UTC`() {
        assertEquals(Instant.parse("2026-09-09T15:25:32Z"), parsePutioTimestamp("2026-09-09T15:25:32"))
    }

    @Test
    fun `a zoned stamp keeps its offset`() {
        assertEquals(Instant.parse("2026-09-09T15:25:32Z"), parsePutioTimestamp("2026-09-09T15:25:32Z"))
        assertEquals(Instant.parse("2026-09-09T13:25:32Z"), parsePutioTimestamp("2026-09-09T15:25:32+02:00"))
    }

    @Test
    fun `anything else is not a timestamp`() {
        assertNull(parsePutioTimestamp(""))
        assertNull(parsePutioTimestamp("yesterday"))
    }
}
