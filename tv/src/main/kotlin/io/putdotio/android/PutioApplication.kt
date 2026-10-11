package io.putdotio.android

import android.app.Application
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.telemetry.SentryConfiguration

/** Starts diagnostics before any screen, service or provider, so a crash at launch is reported too. */
class PutioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PutioDiagnostics.start(
            this,
            SentryConfiguration.of(
                dsn = BuildConfig.PUTIO_SENTRY_DSN,
                environment = BuildConfig.FLAVOR,
                release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
                surface = "tv",
            ),
        )
    }
}
