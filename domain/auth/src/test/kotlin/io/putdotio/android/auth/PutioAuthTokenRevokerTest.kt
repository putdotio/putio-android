package io.putdotio.android.auth

import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PutioAuthTokenRevokerTest {
    private val sent = CopyOnWriteArrayList<Request>()

    @Test
    fun revokeUsesProvidedClientWithRevokedTokenWithoutMutatingLiveSessionToken() = runBlocking {
        val http = responder { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HTTP_OK)
                .message("OK")
                .body("""{"status":"OK"}""".toResponseBody())
                .build()
        }
        val client = PutioClient(PutioConfig(accessToken = LIVE_TOKEN), okHttpClient = http)

        val result = PutioAuthTokenRevoker(client).revoke(REVOKED_TOKEN)

        assertEquals(TokenRevocationResult.REVOKED, result)
        assertEquals(LIVE_TOKEN, client.config.accessToken)
        val recorded = sent.single()
        assertEquals("POST", recorded.method)
        assertEquals("/v2/oauth/grants/logout", recorded.url.encodedPath)
        assertEquals("Token ${REVOKED_TOKEN.reveal()}", recorded.header("Authorization"))
    }

    @Test
    fun revokeWorksWhenLiveSessionHasNoAccessToken() = runBlocking {
        val http = responder { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HTTP_OK)
                .message("OK")
                .body("""{"status":"OK"}""".toResponseBody())
                .build()
        }
        val client = PutioClient(PutioConfig(accessToken = null), okHttpClient = http)

        val result = PutioAuthTokenRevoker(client).revoke(REVOKED_TOKEN)

        assertEquals(TokenRevocationResult.REVOKED, result)
        assertNull(client.config.accessToken)
        assertEquals("Token ${REVOKED_TOKEN.reveal()}", sent.single().header("Authorization"))
    }

    @Test
    fun revokeMapsUnauthorizedServerAndIoFailures() = runBlocking {
        val unauthorizedClient = PutioClient(
            PutioConfig(),
            okHttpClient = responder { request ->
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(HTTP_UNAUTHORIZED)
                    .message("Unauthorized")
                    .body("""{"status_code":401,"error_type":"invalid_token"}""".toResponseBody())
                    .build()
            },
        )
        assertEquals(TokenRevocationResult.REJECTED, PutioAuthTokenRevoker(unauthorizedClient).revoke(REVOKED_TOKEN))

        val serverErrorClient = PutioClient(
            PutioConfig(),
            okHttpClient = responder { request ->
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(HTTP_SERVER_ERROR)
                    .message("Server Error")
                    .body("""{"status_code":500}""".toResponseBody())
                    .build()
            },
        )
        assertEquals(TokenRevocationResult.UNAVAILABLE, PutioAuthTokenRevoker(serverErrorClient).revoke(REVOKED_TOKEN))

        val unavailableClient = PutioClient(
            PutioConfig(),
            okHttpClient = responder { throw IOException("offline") },
        )
        assertEquals(TokenRevocationResult.UNAVAILABLE, PutioAuthTokenRevoker(unavailableClient).revoke(REVOKED_TOKEN))
    }

    @Test
    fun revokeRethrowsCancellationWhenInFlight() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcome = AtomicReference<TokenRevocationResult?>()
        val failure = AtomicReference<Throwable?>()
        val client = PutioClient(
            PutioConfig(),
            okHttpClient = responder { request ->
                started.countDown()
                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(HTTP_OK)
                    .message("OK")
                    .body("""{"status":"OK"}""".toResponseBody())
                    .build()
            },
        )
        val job = async(Dispatchers.Default) {
            try {
                outcome.set(PutioAuthTokenRevoker(client).revoke(REVOKED_TOKEN))
            } catch (error: CancellationException) {
                failure.set(error)
                throw error
            }
        }
        try {
            assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS))
            job.cancelAndJoin()
        } finally {
            release.countDown()
        }

        assertTrue(failure.get() is CancellationException)
        assertNull(outcome.get())
    }

    private fun responder(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    sent += chain.request()
                    handler(chain.request())
                },
            )
            .build()

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_SERVER_ERROR = 500
        const val WAIT_SECONDS = 5L
        const val LIVE_TOKEN = "live-session-token"
        val REVOKED_TOKEN = checkNotNull(AccessToken.parse("revoked-prior-token"))
    }
}
