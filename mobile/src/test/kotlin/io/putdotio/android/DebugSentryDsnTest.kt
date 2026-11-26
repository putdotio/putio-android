package io.putdotio.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugSentryDsnTest {
    @Test
    fun debugBuildsCarryNoDsn() {
        assertEquals("", BuildConfig.PUTIO_SENTRY_DSN)
    }
}
