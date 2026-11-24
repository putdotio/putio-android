package io.putdotio.android

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.putdotio.android.files.FilesPdfDocument
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.android.telemetry.ProductAnalyticsConfiguration
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.telemetry.SentryConfiguration
import kotlin.concurrent.thread

class PutioApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { PutioHttpClient.shared }))
            }
            .build()
    override fun onCreate() {
        super.onCreate()
        val configuration = SentryConfiguration.of(BuildConfig.PUTIO_SENTRY_DSN, BuildConfig.FLAVOR, surface = "tv")
        PutioDiagnostics.start(this, configuration)
        ProductAnalytics.start(
            this,
            ProductAnalyticsConfiguration.of(
                BuildConfig.PUTIO_POSTHOG_TOKEN,
                tv = true,
                BuildConfig.FLAVOR,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
        )
        thread(name = "viewer-cache-clear") { FilesPdfDocument.clearCache(this) }
    }
}
