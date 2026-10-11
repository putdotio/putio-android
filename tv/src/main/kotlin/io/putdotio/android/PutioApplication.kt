package io.putdotio.android

import android.app.Application
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.telemetry.SentryConfiguration

/**
 * Starts diagnostics before any screen or service, so a crash right after launch is reported
 * too. Content providers are created before this runs and stay outside it.
 */
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
