package io.putdotio.android

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class PutioHttpClientTest {
    @Test
    fun sharedClientUsesUnboundedCallTimeout() {
        assertEquals(0, PutioHttpClient.shared.callTimeoutMillis)
    }

    @Test
    fun derivedClientsShareConnectionPoolAndDispatcherWithoutMutatingBase() {
        val derived = PutioHttpClient.newBuilder()
            .callTimeout(DERIVED_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        assertNotSame(PutioHttpClient.shared, derived)
        assertSame(PutioHttpClient.shared.connectionPool, derived.connectionPool)
        assertSame(PutioHttpClient.shared.dispatcher, derived.dispatcher)
        assertEquals(DERIVED_TIMEOUT_MILLIS, derived.callTimeoutMillis)
        assertEquals(0, PutioHttpClient.shared.callTimeoutMillis)
    }

    private companion object {
        const val DERIVED_TIMEOUT_SECONDS = 15L
        const val DERIVED_TIMEOUT_MILLIS = 15_000
    }
}
