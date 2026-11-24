package io.putdotio.android

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Process-wide HTTP pool shared across API, media streaming, downloads, share, and image loading. */
public object PutioHttpClient {
    /** Shared base client with unbounded total call timeout for streaming, downloads, and image fetches. */
    public val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(0L, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Derives a client sharing [shared]'s connection pool, dispatcher, and task runner. */
    public fun newBuilder(): OkHttpClient.Builder = shared.newBuilder()
}
