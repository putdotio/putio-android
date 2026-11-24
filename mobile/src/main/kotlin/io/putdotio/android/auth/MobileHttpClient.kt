package io.putdotio.android.auth

import io.putdotio.android.PutioHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

internal const val MOBILE_API_CALL_TIMEOUT_SECONDS: Long = 30L

internal fun mobileHttpClient(
    callTimeoutMillis: Long = TimeUnit.SECONDS.toMillis(MOBILE_API_CALL_TIMEOUT_SECONDS),
): OkHttpClient =
    PutioHttpClient.newBuilder()
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()
