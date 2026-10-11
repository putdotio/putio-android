package io.putdotio.android.auth

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
        ).forEach { state -> assertNull("$state", state.diagnosticsAllowed()) }
        assertEquals(true, MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired).diagnosticsAllowed())
        assertEquals(true, MobileAuthState.AwaitingOAuthCallback.diagnosticsAllowed())

        val optedOut = MobileAccount(7, "fixture", "fixture@example.invalid", diagnosticsEnabled = false)
        assertEquals(false, MobileAuthState.SignedIn(optedOut, MobileAuthSessionId(3)).diagnosticsAllowed())
    }
}
