package io.putdotio.android.tv.auth

import io.putdotio.android.telemetry.DiagnosticsAnswer
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
        ).forEach { state -> assertNull("$state", state.diagnosticsAnswer()) }
        val linking = TvAuthState.Linking(TvLinkPhase.RequestingCode, sessionExpired = true)
        assertEquals(DiagnosticsAnswer.SignedOut, linking.diagnosticsAnswer())

        val optedOut = TvAccount(7, "fixture", "fixture@example.invalid", diagnosticsEnabled = false)
        val signedIn = TvAuthState.SignedIn(optedOut, TvAuthSessionId(3))
        assertEquals(DiagnosticsAnswer.Account(allowed = false), signedIn.diagnosticsAnswer())
    }
}
