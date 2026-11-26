package io.putdotio.android

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

public object PutioHttpClient {
    public val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(0L, TimeUnit.MILLISECONDS)
            .build()
    }

    public fun newBuilder(): OkHttpClient.Builder = shared.newBuilder()
}
