package io.putdotio.android.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentryConfigurationTest {
    @Test
    fun eventsPostThroughTheRelay() {
        val configuration = SentryConfiguration.of(
            dsn = "https://synthetickey@o0.ingest.example.invalid/4500000000000001",
            environment = "nightly",
            release = "io.put.putio.nightly@0.1.0-nightly+1",
            surface = "tv",
        )

        assertEquals("https://synthetickey@relay.put.io/4500000000000001", configuration?.dsn)
        assertEquals(
            "https://k@relay.put.io/12",
            SentryConfiguration.relayDsn("https://k:legacy@sentry.example.invalid/base/12/"),
        )
    }

    @Test
    fun anEmptyOrUnusableDsnIsTheKillSwitch() {
        listOf(
            "", "  ", "https://sentry.example.invalid/12", "https://k@host/not-a-project", "ftp://k@host/12",
            "https://k@/12", "not a dsn", "https://k\"@host/12",
        ).forEach { dsn ->
            assertNull(dsn, SentryConfiguration.of(dsn, "production", "r", "mobile"))
        }
    }
}
