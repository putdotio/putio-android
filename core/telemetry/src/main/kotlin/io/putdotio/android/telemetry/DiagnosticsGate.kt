package io.putdotio.android.telemetry

/** What one started SDK's callbacks and transport ask before anything leaves the process. */
internal interface DiagnosticsFilter {
    val isCapturing: Boolean

    /** Whether an event recorded at [timestampMillis] may be sent now. */
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
 * Runs Sentry only while the build carries a DSN and the account allows diagnostics. Capture
 * stops the moment either says no, before the SDK has finished closing; anything recorded
 * before an opt-out is deleted, and nothing recorded before an opt-in is sent after it.
 */
internal class DiagnosticsGate(
    private val configuration: SentryConfiguration?,
    private val consent: DiagnosticsConsent,
    private val runtime: DiagnosticsRuntime,
) {
    /**
     * One uninterrupted on-period. Each start gets its own, and an ended period never reopens,
     * so an SDK still closing after an opt-out cannot upload through a later opt-in.
     */
    private inner class OnPeriod(private val since: Long) : DiagnosticsFilter {
        override val isCapturing: Boolean
            get() = current === this

        // Exit-info reports (ANRs, native crashes) are read back at start, stamped when they happened.
        override fun admits(timestampMillis: Long): Boolean = isCapturing && timestampMillis >= since
    }

    @Volatile
    private var current: OnPeriod? = null

    val isCapturing: Boolean
        get() = current != null

    @Synchronized
    fun start() = apply(launch = true)

    @Synchronized
    fun update(session: DiagnosticsSession) {
        if (consent.update(session)) apply() else consent.persist()
    }

    @Synchronized
    fun accountAnswered(key: Any, enabled: Boolean) {
        if (consent.accountAnswered(key, enabled)) apply() else consent.persist()
    }

    fun capture(failure: TelemetryFailure) {
        if (isCapturing) runtime.capture(failure)
    }

    // Capture stops before the answer is written; the purge is queued once it is on disk, so a
    // process killed in between still launches with the opt-out.
    private fun apply(launch: Boolean = false) {
        val capturing = configuration != null && consent.isEnabled
        if (!launch && capturing == isCapturing) {
            consent.persist()
            return
        }
        if (configuration != null && capturing) {
            consent.persist()
            val period = OnPeriod(consent.enabledSince ?: Long.MAX_VALUE)
            current = period
            runtime.start(configuration, period, launch)
        } else {
            current = null
            consent.persist()
            runtime.stopAndPurge()
        }
    }
}
