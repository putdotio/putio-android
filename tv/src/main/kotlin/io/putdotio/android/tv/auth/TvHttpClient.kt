package io.putdotio.android.tv.auth

import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

internal fun tvHttpClient(pause: (Long) -> Unit = ::pauseFor): OkHttpClient =
    OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(RateLimitRetry(pause))
        .build()

internal class RateLimitRetry(private val pause: (Long) -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != HTTP_TOO_MANY_REQUESTS || request.method !in IDEMPOTENT_METHODS) return response
        val waitMillis = response.retryAfterMillis() ?: return response
        response.close()
        pause(waitMillis)
        return chain.proceed(request)
    }
}

private fun Response.retryAfterMillis(): Long? {
    val seconds = header(RETRY_AFTER)?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        ?: return DEFAULT_RETRY_WAIT_MILLIS
    return TimeUnit.SECONDS.toMillis(seconds).takeIf { it <= MAX_RETRY_WAIT_MILLIS }
}

private fun pauseFor(millis: Long) {
    try {
        Thread.sleep(millis)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Rate-limit wait interrupted").apply { initCause(interrupted) }
    }
}

private const val CALL_TIMEOUT_SECONDS = 10L
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val RETRY_AFTER = "Retry-After"
private const val DEFAULT_RETRY_WAIT_MILLIS = 1_000L
private const val MAX_RETRY_WAIT_MILLIS = 5_000L
private val IDEMPOTENT_METHODS = setOf("GET", "HEAD")
