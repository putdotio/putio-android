package io.putdotio.android.telemetry

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences

/**
 * Runs Sentry only while the build has a DSN and diagnostics are allowed: the signed-in account's
 * `diagnostics_enabled`, and allowed while signed out or before any account answered. The answer
 * is kept so a cold launch honours an opt-out before the session restores.
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
        if (!::preferences.isInitialized || allowed == this.allowed) return
        apply(allowed, launch = false)
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
            SentryBoundary.stop(SentryBoundary.cacheDir(application))
        }
    }
}
