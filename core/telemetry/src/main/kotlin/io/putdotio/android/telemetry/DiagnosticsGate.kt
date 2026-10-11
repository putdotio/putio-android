package io.putdotio.android.telemetry

/** Where the session stands, as far as the account's `diagnostics_enabled` is concerned. */
public sealed interface DiagnosticsSession {
    /** Restoring, validating, or signing out: the last known answer stands. */
    public data object Unresolved : DiagnosticsSession

    /** Nobody is signed in, so no account has opted out: diagnostics are on. */
    public data object SignedOut : DiagnosticsSession

    /** [key] names the signed-in session; [diagnosticsEnabled] is what its account answered at sign-in. */
    public data class SignedIn(
        val key: Any,
        val diagnosticsEnabled: Boolean,
    ) : DiagnosticsSession
}

/** Writes synchronously, so an opt-out is on disk before its purge is queued. */
internal interface DiagnosticsConsentStore {
    /** The last signed-in account's answer; true once signed out. */
    var answer: Boolean

    /** When the current uninterrupted on-period began, in epoch milliseconds; null while off. */
    var enabledSince: Long?
}

/** What one started SDK's callbacks and transport ask before anything leaves the process. */
internal interface DiagnosticsFilter {
    val isCapturing: Boolean

    fun admits(timestampMillis: Long): Boolean
}

internal interface DiagnosticsRuntime {
    /** [launch] starts before `Application.onCreate` returns, so a crash right after launch is caught. */
    fun start(configuration: SentryConfiguration, filter: DiagnosticsFilter, launch: Boolean)

    /** Closes the SDK and deletes every event, crash report, and session waiting for upload. */
    fun stopAndPurge()

    fun capture(failure: TelemetryFailure)
}

/**
 * Runs Sentry only while the build carries a DSN and the account allows diagnostics: the
 * signed-in account's `diagnostics_enabled`, and on while signed out or before any account
 * answered. The last answer is kept so a cold launch honours an opt-out before the session
 * restores. Capture stops the moment either says no; anything recorded before an opt-out is
 * deleted, and nothing recorded before an opt-in is sent after it.
 */
internal class DiagnosticsGate(
    private val configuration: SentryConfiguration?,
    private val store: DiagnosticsConsentStore,
    private val runtime: DiagnosticsRuntime,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * One uninterrupted on-period. Each start gets its own, and an ended period never reopens,
     * so an SDK still closing after an opt-out cannot upload through a later opt-in.
     */
    private inner class OnPeriod(private val since: Long) : DiagnosticsFilter {
        override val isCapturing: Boolean
            get() = current === this

        // ANRs are read back from the OS at start, stamped when they happened.
        override fun admits(timestampMillis: Long): Boolean = isCapturing && timestampMillis >= since
    }

    @Volatile
    private var current: OnPeriod? = null
    private var answer: Boolean = store.answer
    private var enabledSince: Long? = store.enabledSince
    private var signedInKey: Any? = null

    val isCapturing: Boolean
        get() = current != null

    @Synchronized
    fun start() = apply(launch = true)

    @Synchronized
    fun update(session: DiagnosticsSession) {
        signedInKey = (session as? DiagnosticsSession.SignedIn)?.key
        when (session) {
            DiagnosticsSession.Unresolved -> Unit
            DiagnosticsSession.SignedOut -> answer = true
            is DiagnosticsSession.SignedIn -> answer = session.diagnosticsEnabled
        }
        apply()
    }

    /** The account answered again; ignored unless [key] is still the signed-in session. */
    @Synchronized
    fun accountAnswered(key: Any, enabled: Boolean) {
        if (key != signedInKey) return
        answer = enabled
        apply()
    }

    fun capture(failure: TelemetryFailure) {
        if (isCapturing) runtime.capture(failure)
    }

    // Capture stops before the answer is written; the purge is queued once it is on disk, so a
    // process killed in between still launches with the opt-out.
    private fun apply(launch: Boolean = false) {
        enabledSince = if (answer) enabledSince ?: now() else null
        val since = enabledSince
        val capturing = configuration != null && since != null
        if (!launch && capturing == isCapturing) {
            persist()
        } else if (configuration != null && since != null) {
            persist()
            val period = OnPeriod(since)
            current = period
            runtime.start(configuration, period, launch)
        } else {
            current = null
            persist()
            runtime.stopAndPurge()
        }
    }

    private fun persist() {
        if (store.answer != answer) store.answer = answer
        if (store.enabledSince != enabledSince) store.enabledSince = enabledSince
    }
}
