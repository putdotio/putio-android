package io.putdotio.android

import android.app.Application
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.telemetry.SentryConfiguration

class PutioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val configuration = SentryConfiguration.of(BuildConfig.PUTIO_SENTRY_DSN, BuildConfig.FLAVOR, surface = "tv")
        PutioDiagnostics.start(this, configuration)
    }
}
