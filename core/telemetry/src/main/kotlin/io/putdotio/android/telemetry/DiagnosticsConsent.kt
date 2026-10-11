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

internal interface DiagnosticsConsentStore {
    /** The last signed-in account's answer; null when none is kept. */
    var answer: Boolean?

    /** When the current uninterrupted on-period began, in epoch milliseconds; null while off. */
    var enabledSince: Long?
}

/**
 * Whether crash and error reports may leave the device: the signed-in account's
 * `diagnostics_enabled`, and on while signed out or before any account answered. The last
 * answer is kept so a cold launch honours an opt-out before the session restores.
 */
internal class DiagnosticsConsent(
    private val store: DiagnosticsConsentStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var isEnabled: Boolean = store.answer ?: true
        private set

    private var signedInKey: Any? = null

    init {
        recordOnPeriod()
    }

    val enabledSince: Long?
        get() = store.enabledSince

    /** Applies the session's answer; true when [isEnabled] changed. */
    fun update(session: DiagnosticsSession): Boolean =
        when (session) {
            DiagnosticsSession.Unresolved -> {
                signedInKey = null
                false
            }
            DiagnosticsSession.SignedOut -> {
                signedInKey = null
                store.answer = null
                apply(true)
            }
            is DiagnosticsSession.SignedIn -> {
                signedInKey = session.key
                answer(session.diagnosticsEnabled)
            }
        }

    /**
     * The signed-in account answered again, from its settings or the Privacy toggle. Ignored
     * unless [key] is still the signed-in session, so a departed session cannot overrule the next.
     */
    fun accountAnswered(key: Any, enabled: Boolean): Boolean =
        key == signedInKey && answer(enabled)

    private fun answer(enabled: Boolean): Boolean {
        store.answer = enabled
        return apply(enabled)
    }

    private fun apply(enabled: Boolean): Boolean {
        if (enabled == isEnabled) return false
        isEnabled = enabled
        recordOnPeriod()
        return true
    }

    private fun recordOnPeriod() {
        when {
            !isEnabled -> store.enabledSince = null
            store.enabledSince == null -> store.enabledSince = now()
        }
    }
}
