package io.putdotio.android.auth

import io.putdotio.android.telemetry.DiagnosticsAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileDiagnosticsTest {
    @Test
    fun restoringKeepsTheLastAnswerSignedOutIsOnAndSignedInTakesTheAccounts() {
        listOf(
            MobileAuthState.Initializing,
            MobileAuthState.RestoringSession,
            MobileAuthState.ValidatingSession(SessionValidationSource.RESTORE),
            MobileAuthState.ValidationUnavailable(SessionValidationSource.RETRY),
            MobileAuthState.SigningOut,
            MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable),
        ).forEach { state -> assertNull("$state", state.diagnosticsAnswer()) }
        listOf(
            MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired),
            MobileAuthState.AwaitingOAuthCallback,
        ).forEach { state -> assertEquals("$state", DiagnosticsAnswer.SignedOut, state.diagnosticsAnswer()) }

        val optedOut = MobileAccount(7, "fixture", "fixture@example.invalid", diagnosticsEnabled = false)
        val signedIn = MobileAuthState.SignedIn(optedOut, MobileAuthSessionId(3))
        assertEquals(DiagnosticsAnswer.Account(allowed = false), signedIn.diagnosticsAnswer())
    }
}
