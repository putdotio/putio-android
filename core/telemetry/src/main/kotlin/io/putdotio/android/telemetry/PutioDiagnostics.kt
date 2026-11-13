package io.putdotio.android.telemetry

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences

public sealed interface DiagnosticsAnswer {
    public data object SignedOut : DiagnosticsAnswer

    public data class Account(public val allowed: Boolean) : DiagnosticsAnswer
}

public object PutioDiagnostics {
    private const val ALLOWED = "allowed"
    private const val CACHE_FORMAT = "cache_format"
    private const val SIGNED_IN = "signed_in"
    private const val SESSIONLESS_CACHE = 1
    private lateinit var application: Application
    private lateinit var preferences: SharedPreferences
    private var configuration: SentryConfiguration? = null

    private val allowed: Boolean get() = preferences.getBoolean(ALLOWED, true)

    @Synchronized
    public fun start(application: Application, configuration: SentryConfiguration?) {
        this.application = application
        this.configuration = configuration
        val preferences = application.getSharedPreferences("io.put.diagnostics", Context.MODE_PRIVATE)
        this.preferences = preferences
        val stale = preferences.getInt(CACHE_FORMAT, 0) < SESSIONLESS_CACHE
        if (stale) {
            val files = DiagnosticsFiles(application)
            SentryBoundary.submit { wipe(files, preferences) }
        }
        apply(allowed, launch = true, inline = !stale)
    }

    // commit, not apply: an apply() still pending when an activity stops makes the main thread wait.
    @SuppressLint("ApplySharedPref")
    private fun wipe(files: DiagnosticsFiles, preferences: SharedPreferences) {
        files.cache.deleteRecursively()
        preferences.edit().putInt(CACHE_FORMAT, SESSIONLESS_CACHE).commit()
    }

    public fun update(allowed: Boolean) {
        update(DiagnosticsAnswer.Account(allowed))
    }

    @Synchronized
    public fun update(answer: DiagnosticsAnswer) {
        if (!::preferences.isInitialized) return
        val allowed = when (answer) {
            DiagnosticsAnswer.SignedOut -> true
            is DiagnosticsAnswer.Account -> answer.allowed
        }
        val held = SentryBoundary.current?.confirmed == false
        val signedIn = answer is DiagnosticsAnswer.Account
        val leftAnAccount = answer == DiagnosticsAnswer.SignedOut && preferences.getBoolean(SIGNED_IN, false)
        if (signedIn != preferences.getBoolean(SIGNED_IN, false)) {
            preferences.edit().putBoolean(SIGNED_IN, signedIn).apply()
        }
        when {
            held && leftAnAccount -> {
                apply(false, launch = false)
                apply(true, launch = false)
            }
            held && allowed -> SentryBoundary.confirm()
            held || allowed != this.allowed -> apply(allowed, launch = false)
        }
        if (allowed == this.allowed) return
        preferences.edit().putBoolean(ALLOWED, allowed).apply()
    }

    public fun capture(failure: TelemetryFailure): String? = SentryBoundary.capture(failure)

    private fun apply(allowed: Boolean, launch: Boolean, inline: Boolean = launch) {
        val configuration = configuration
        if (configuration != null && allowed) {
            SentryBoundary.start(application, configuration, launch, inline)
        } else {
            SentryBoundary.stop(DiagnosticsFiles(application))
        }
    }
}
