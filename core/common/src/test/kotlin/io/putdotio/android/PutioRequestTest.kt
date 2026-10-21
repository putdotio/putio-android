package io.putdotio.android

import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.errors.PutioTransportException
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PutioRequestTest {
    @Test
    fun cancellationPropagatesInsteadOfBecomingAFailure() {
        val cancellation = CancellationException("screen closed")
        try {
            runBlocking { putioRequest<Unit> { throw cancellation } }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun sdkAndUnexpectedErrorsBecomeTypedFailures() = runBlocking {
        val unauthorized = putioRefusal(401, putioErrorBody(401, "invalid_token"))
        val auth = putioRequest<Unit> { throw unauthorized } as PutioResult.Failure
        assertTrue(auth.failure is PutioFailure.AuthenticationRequired)
        assertSame(unauthorized, auth.failure.cause)

        val transport =
            PutioTransportException(PutioRequestData("GET", "https://api.put.io/v2/files/list"), IOException("reset"))
        val offline = putioRequest<Unit> { throw transport } as PutioResult.Failure
        assertTrue(offline.failure is PutioFailure.NetworkUnavailable)

        val unexpected = IllegalStateException("broken mapper")
        assertEquals(
            PutioResult.Failure(PutioFailure.Unexpected(unexpected)),
            putioRequest<Unit> { throw unexpected },
        )
        assertEquals(PutioResult.Success(7), putioRequest { 7 })
    }
}
