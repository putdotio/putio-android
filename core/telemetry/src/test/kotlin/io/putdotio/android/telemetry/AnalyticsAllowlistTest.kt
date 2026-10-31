package io.putdotio.android.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsAllowlistTest {
    @Test
    fun everyScreenValueLeaves() {
        AnalyticsEvent.Screen.entries.forEach { screen ->
            assertEquals(
                mapOf("screen" to screen.value),
                AnalyticsAllowlist.allowedProperties("screen_viewed", mapOf("screen" to screen.value)),
            )
        }
    }
}
