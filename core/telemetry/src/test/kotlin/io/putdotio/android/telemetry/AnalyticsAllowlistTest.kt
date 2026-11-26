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

    @Test
    fun everySettingValueLeaves() {
        AnalyticsEvent.Setting.entries.forEach { setting ->
            assertEquals(
                mapOf("setting" to setting.value, "value" to "on"),
                AnalyticsAllowlist.allowedProperties(
                    "settings_changed",
                    mapOf("setting" to setting.value, "value" to "on"),
                ),
            )
        }
        listOf("on", "off").forEach { value ->
            assertEquals(
                mapOf("setting" to "beta.putio_cast_receiver", "value" to value),
                AnalyticsAllowlist.allowedProperties(
                    "settings_changed",
                    mapOf("setting" to "beta.putio_cast_receiver", "value" to value),
                ),
            )
        }
    }
}
