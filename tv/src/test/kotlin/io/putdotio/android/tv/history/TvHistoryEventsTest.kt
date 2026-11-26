package io.putdotio.android.tv.history

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class TvHistoryEventsTest {
    @Test
    fun dayFormatterCachesPerLocaleAndSkeleton() {
        val usShortFirst = tvHistoryDayFormatter(Locale.US, "MMMd")
        val usShortSecond = tvHistoryDayFormatter(Locale.US, "MMMd")
        val usWithYear = tvHistoryDayFormatter(Locale.US, "MMMdyyyy")
        val deShort = tvHistoryDayFormatter(Locale.GERMANY, "MMMd")

        assertSame(usShortFirst, usShortSecond)
        assertNotSame(usShortFirst, usWithYear)
        assertNotSame(usShortFirst, deShort)
    }

    @Test
    fun dayLabelFormatsSameYearAndPriorYearDates() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val today = LocalDate.of(2026, 9, 12)

        assertEquals("Today", TvHistoryDay(today, null).label(today, resources, Locale.US))
        assertEquals("Yesterday", TvHistoryDay(today.minusDays(1), null).label(today, resources, Locale.US))
        assertEquals("Sep 10", TvHistoryDay(LocalDate.of(2026, 9, 10), null).label(today, resources, Locale.US))
        assertEquals("Sep 10, 2025", TvHistoryDay(LocalDate.of(2025, 9, 10), null).label(today, resources, Locale.US))
    }
}
