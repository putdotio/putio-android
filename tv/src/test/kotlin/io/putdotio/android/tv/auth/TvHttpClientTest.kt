package io.putdotio.android.tv.auth

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class TvHttpClientTest {
    private val pauses = mutableListOf<Long>()
    private val answers = ArrayDeque<Pair<Int, String?>>()
    private var calls = 0

    private val client: OkHttpClient = tvHttpClient { pauses += it }
        .newBuilder()
        .addInterceptor(
            Interceptor { chain ->
                calls++
                val (code, retryAfter) = answers.removeFirst()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("")
                    .apply { retryAfter?.let { header("Retry-After", it) } }
                    .body("{}".toResponseBody())
                    .build()
            },
        )
        .build()

    @Test
    fun requestsFailAfterTenSeconds() {
        assertEquals(10_000, tvHttpClient().callTimeoutMillis)
    }

    @Test
    fun aRateLimitedReadIsRetriedOnceAfterRetryAfter() {
        answers += 429 to "2"
        answers += 200 to null

        val code = client.newCall(Request.Builder().url(URL).build()).execute().use { it.code }

        assertEquals(200, code)
        assertEquals(listOf(2_000L), pauses)
    }

    @Test
    fun aSecondRateLimitReachesTheCaller() {
        answers += 429 to "1"
        answers += 429 to "1"

        val code = client.newCall(Request.Builder().url(URL).build()).execute().use { it.code }

        assertEquals(429, code)
        assertEquals(2, calls)
    }

    @Test
    fun aRateLimitedWriteIsNotRetried() {
        answers += 429 to "1"

        val request = Request.Builder().url(URL).post("".toRequestBody()).build()
        val code = client.newCall(request).execute().use { it.code }

        assertEquals(429, code)
        assertEquals(1, calls)
        assertEquals(emptyList<Long>(), pauses)
    }

    private companion object {
        const val URL = "https://api.put.io/v2/files/list"
    }
}
