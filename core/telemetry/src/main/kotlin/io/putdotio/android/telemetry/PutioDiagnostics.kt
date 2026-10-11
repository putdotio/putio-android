package io.putdotio.android.telemetry

import android.app.Application
import android.content.Context
import android.util.Log
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The apps' only way to Sentry; `docs/behavior.md#diagnostics` describes the rules. Sentry runs
 * only while the build carries a DSN and the account allows diagnostics: the apps report every
 * session change and every answer the account gives, and the gate starts or stops the SDK in the
 * running process.
 */
public object PutioDiagnostics {
    @Volatile
    private var gate: DiagnosticsGate? = null

    /** Once per process, from `Application.onCreate`; a null [configuration] keeps Sentry off. */
    @Synchronized
    public fun start(application: Application, configuration: SentryConfiguration?) {
        gate = DiagnosticsGate(
            configuration = configuration,
            consent = DiagnosticsConsent(PreferencesConsentStore(application)),
            runtime = SentryRuntime(application),
        ).also(DiagnosticsGate::start)
    }

    public fun sessionChanged(session: DiagnosticsSession) {
        gate?.update(session)
    }

    /** The account behind [sessionKey] answered from its settings or the Privacy toggle. */
    public fun accountAnswered(sessionKey: Any, diagnosticsEnabled: Boolean) {
        gate?.accountAnswered(sessionKey, diagnosticsEnabled)
    }

    public fun capture(failure: TelemetryFailure) {
        gate?.capture(failure)
    }
}

private class PreferencesConsentStore(context: Context) : DiagnosticsConsentStore {
    private val preferences = context.getSharedPreferences("io.put.diagnostics", Context.MODE_PRIVATE)

    override var answer: Boolean?
        get() = if (preferences.contains(ANSWER)) preferences.getBoolean(ANSWER, true) else null
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove(ANSWER) else editor.putBoolean(ANSWER, value)
            editor.apply()
        }

    override var enabledSince: Long?
        get() = if (preferences.contains(ENABLED_SINCE)) preferences.getLong(ENABLED_SINCE, 0) else null
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove(ENABLED_SINCE) else editor.putLong(ENABLED_SINCE, value)
            editor.apply()
        }

    private companion object {
        const val ANSWER = "diagnostics_enabled"
        const val ENABLED_SINCE = "enabled_since"
    }
}

/**
 * Starts the SDK at launch on the calling thread, so the crash handler is in place before the
 * first screen or service; every later start and close runs on one background thread, in the
 * order the gate asked. Closing flushes through the ended period's transport gate, so pending
 * envelopes land on disk, and the purge after it deletes them.
 */
private class SentryRuntime(private val context: Context) : DiagnosticsRuntime {
    override fun start(configuration: SentryConfiguration, filter: DiagnosticsFilter, launch: Boolean) {
        val step = {
            SentryAndroid.init(context) { options ->
                SentryBoundary.configure(options, configuration, filter, diagnosticsCacheDir(context.cacheDir).path)
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false
            }
        }
        if (launch) guarded(step) else executor.execute { guarded(step) }
    }

    override fun stopAndPurge() =
        executor.execute {
            guarded {
                try {
                    if (Sentry.isEnabled()) Sentry.close()
                } finally {
                    purgeDiagnosticsCache(context.cacheDir)
                }
            }
        }

    // Queued behind any start, so a failure right after an opt-in reaches the new SDK.
    override fun capture(failure: TelemetryFailure) =
        executor.execute { guarded { Sentry.captureEvent(SentryBoundary.event(failure)) } }

    // Diagnostics must never take the app down; a failed step is logged and the next one runs.
    @Suppress("TooGenericExceptionCaught")
    private fun guarded(step: () -> Unit) {
        try {
            step()
        } catch (error: Exception) {
            Log.w(LOG_TAG, "diagnostics step failed: ${error.javaClass.simpleName}")
        }
    }

    private companion object {
        const val LOG_TAG = "PutioDiagnostics"

        // One thread at most, alive only while there is work, shared by every start in the process.
        val executor = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue())
    }
}

/** Where Sentry keeps envelopes, crash reports, sessions, and scope snapshots waiting for upload. */
internal fun diagnosticsCacheDir(cacheDir: File): File = File(cacheDir, "io.put.diagnostics")

/** Deletes everything Sentry kept for later, including the SDK's default directory. */
internal fun purgeDiagnosticsCache(cacheDir: File) {
    diagnosticsCacheDir(cacheDir).deleteRecursively()
    File(cacheDir, "sentry").deleteRecursively()
}
