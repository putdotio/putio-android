package io.putdotio.android.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentryConfigurationTest {
    @Test
    fun eventsPostThroughTheRelayAndAnEmptyDsnIsTheKillSwitch() {
        val configuration = SentryConfiguration.of(
            dsn = "https://synthetickey@o0.ingest.example.invalid/4500000000000001",
            environment = "nightly",
            surface = "tv",
        )

        assertEquals("https://synthetickey@relay.put.io/4500000000000001", configuration?.dsn)
        assertNull(SentryConfiguration.of("", "production", "mobile"))
    }
}
