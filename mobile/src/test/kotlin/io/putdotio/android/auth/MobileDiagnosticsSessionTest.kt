package io.putdotio.android.auth

import io.putdotio.android.telemetry.DiagnosticsSession
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which auth states keep, turn on, or take the account's diagnostics answer. */
class MobileDiagnosticsSessionTest {
    @Test
    fun restoringValidatingAndSigningOutKeepTheLastAnswer() {
        listOf(
            MobileAuthState.Initializing,
            MobileAuthState.RestoringSession,
            MobileAuthState.ValidatingSession(SessionValidationSource.RESTORE),
            MobileAuthState.ValidationUnavailable(SessionValidationSource.RETRY),
            MobileAuthState.SigningOut,
        ).forEach { state -> assertEquals("$state", DiagnosticsSession.Unresolved, state.diagnosticsSession()) }
    }

    @Test
    fun signedOutCountsAsOn() {
        listOf(
            MobileAuthState.SignedOut(),
            MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired),
            MobileAuthState.AwaitingOAuthCallback,
        ).forEach { state -> assertEquals("$state", DiagnosticsSession.SignedOut, state.diagnosticsSession()) }
    }

    @Test
    fun signedInCarriesTheAccountsAnswerUnderItsSessionKey() {
        val account = MobileAccount(
            userId = 7,
            username = "fixture",
            email = "fixture@example.invalid",
            diagnosticsEnabled = false,
        )

        assertEquals(
            DiagnosticsSession.SignedIn(MobileSessionKey(7, MobileAuthSessionId(3)), diagnosticsEnabled = false),
            MobileAuthState.SignedIn(account, MobileAuthSessionId(3)).diagnosticsSession(),
        )
    }
}
