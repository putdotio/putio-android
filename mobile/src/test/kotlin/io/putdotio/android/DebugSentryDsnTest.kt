package io.putdotio.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit and Robolectric tests run debug variants, so a DSN given to a release build never starts Sentry there. */
class DebugSentryDsnTest {
    @Test
    fun debugBuildsCarryNoDsn() {
        assertEquals("", BuildConfig.PUTIO_SENTRY_DSN)
    }
}
