package io.putdotio.android.tv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvDiagnosticsTest {
    @Test
    fun restoringKeepsTheLastAnswerLinkingIsOnAndSignedInTakesTheAccounts() {
        listOf(
            TvAuthState.Initializing,
            TvAuthState.RestoringSession,
            TvAuthState.ValidatingSession(TvSessionValidationSource.RESTORE),
            TvAuthState.ValidationUnavailable(TvSessionValidationSource.RETRY),
            TvAuthState.SigningOut,
            TvAuthState.Linking(TvLinkPhase.Stopped(TvLinkStop.StorageUnavailable)),
        ).forEach { state -> assertNull("$state", state.diagnosticsAllowed()) }
        assertEquals(true, TvAuthState.Linking(TvLinkPhase.RequestingCode, sessionExpired = true).diagnosticsAllowed())

        val optedOut = TvAccount(7, "fixture", "fixture@example.invalid", diagnosticsEnabled = false)
        assertEquals(false, TvAuthState.SignedIn(optedOut, TvAuthSessionId(3)).diagnosticsAllowed())
    }
}
