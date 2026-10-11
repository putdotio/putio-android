package io.putdotio.android.tv.auth

import io.putdotio.android.telemetry.DiagnosticsSession
import io.putdotio.android.tv.TvSessionKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which auth states keep, turn on, or take the account's diagnostics answer. */
class TvDiagnosticsSessionTest {
    @Test
    fun restoringValidatingAndSigningOutKeepTheLastAnswer() {
        listOf(
            TvAuthState.Initializing,
            TvAuthState.RestoringSession,
            TvAuthState.ValidatingSession(TvSessionValidationSource.RESTORE),
            TvAuthState.ValidationUnavailable(TvSessionValidationSource.RETRY),
            TvAuthState.SigningOut,
        ).forEach { state -> assertEquals("$state", DiagnosticsSession.Unresolved, state.diagnosticsSession()) }
    }

    @Test
    fun theDeviceCodeScreenIsSignedOutSoOn() {
        listOf(
            TvAuthState.Linking(TvLinkPhase.RequestingCode),
            TvAuthState.Linking(TvLinkPhase.Validating, sessionExpired = true),
        ).forEach { state -> assertEquals("$state", DiagnosticsSession.SignedOut, state.diagnosticsSession()) }
    }

    @Test
    fun signedInCarriesTheAccountsAnswerUnderItsSessionKey() {
        val account = TvAccount(
            userId = 7,
            username = "fixture",
            email = "fixture@example.invalid",
            diagnosticsEnabled = false,
        )

        assertEquals(
            DiagnosticsSession.SignedIn(TvSessionKey(7, TvAuthSessionId(3)), diagnosticsEnabled = false),
            TvAuthState.SignedIn(account, TvAuthSessionId(3)).diagnosticsSession(),
        )
    }
}
