package io.putdotio.android.telemetry

/** What Sentry's callbacks ask before anything leaves the process. */
internal interface DiagnosticsFilter {
    val isCapturing: Boolean

    /** Whether an event recorded at [timestampMillis] may be sent now. */
    fun admits(timestampMillis: Long): Boolean
}

internal interface DiagnosticsRuntime {
    fun start(configuration: SentryConfiguration, filter: DiagnosticsFilter)

    /** Closes the SDK and deletes every event, crash report, and session waiting for upload. */
    fun stopAndPurge()
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
) : DiagnosticsFilter {
    @Volatile
    override var isCapturing: Boolean = false
        private set

    @Volatile
    private var capturingSince: Long = Long.MAX_VALUE

    @Synchronized
    fun start() = apply(launch = true)

    @Synchronized
    fun update(session: DiagnosticsSession) {
        if (consent.update(session)) apply()
    }

    @Synchronized
    fun accountAnswered(key: Any, enabled: Boolean) {
        if (consent.accountAnswered(key, enabled)) apply()
    }

    // Exit-info reports (ANRs, native crashes) are read back at start, stamped when they happened.
    override fun admits(timestampMillis: Long): Boolean = isCapturing && timestampMillis >= capturingSince

    private fun apply(launch: Boolean = false) {
        val capturing = configuration != null && consent.isEnabled
        if (!launch && capturing == isCapturing) return
        capturingSince = consent.enabledSince ?: Long.MAX_VALUE
        isCapturing = capturing
        if (configuration != null && capturing) {
            runtime.start(configuration, this)
        } else {
            runtime.stopAndPurge()
        }
    }
}
