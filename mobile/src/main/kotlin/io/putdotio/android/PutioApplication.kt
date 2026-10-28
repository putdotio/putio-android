package io.putdotio.android

import android.app.Application
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.android.telemetry.ProductAnalyticsConfiguration
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.telemetry.SentryConfiguration
import io.putdotio.android.viewer.MobilePdfDocument

class PutioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val configuration = SentryConfiguration.of(BuildConfig.PUTIO_SENTRY_DSN, BuildConfig.FLAVOR, surface = "mobile")
        PutioDiagnostics.start(this, configuration)
        ProductAnalytics.start(
            this,
            ProductAnalyticsConfiguration.of(
                BuildConfig.PUTIO_POSTHOG_TOKEN,
                tv = false,
                BuildConfig.FLAVOR,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
        )
        // A PDF copy a viewer left when the process died; restoring from Recents may never reopen it.
        MobilePdfDocument.clearCache(this)
    }
}
