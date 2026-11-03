package io.putdotio.android.telemetry

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences

/**
 * Runs Sentry only while the build has a DSN and diagnostics are allowed: the signed-in account's
 * `diagnostics_enabled`, and allowed while signed out. A cold launch after an opt-out stays off; any
 * other launch starts Sentry but keeps envelopes on the device until the first answer, since the
 * account may have opted out elsewhere.
 */
public object PutioDiagnostics {
    private const val ALLOWED = "allowed"
    private lateinit var application: Application
    private lateinit var preferences: SharedPreferences
    private var configuration: SentryConfiguration? = null

    private val allowed: Boolean get() = preferences.getBoolean(ALLOWED, true)

    /** Once per process, from `Application.onCreate`; a null [configuration] keeps Sentry off. */
    @Synchronized
    public fun start(application: Application, configuration: SentryConfiguration?) {
        this.application = application
        this.configuration = configuration
        preferences = application.getSharedPreferences("io.put.diagnostics", Context.MODE_PRIVATE)
        apply(allowed, launch = true)
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    public fun update(allowed: Boolean) {
        if (!::preferences.isInitialized) return
        val awaitingAnswer = SentryBoundary.current?.confirmed == false
        when {
            awaitingAnswer && allowed -> SentryBoundary.confirm()
            awaitingAnswer || allowed != this.allowed -> apply(allowed, launch = false)
        }
        if (allowed == this.allowed) return
        // On disk at once, so a process killed before the queued purge relaunches with the opt-out.
        preferences.edit().putBoolean(ALLOWED, allowed).commit()
    }

    public fun capture(failure: TelemetryFailure) {
        SentryBoundary.capture(failure)
    }

    private fun apply(allowed: Boolean, launch: Boolean) {
        val configuration = configuration
        if (configuration != null && allowed) {
            SentryBoundary.start(application, configuration, launch)
        } else {
            SentryBoundary.stop(DiagnosticsFiles(application))
        }
    }
}
