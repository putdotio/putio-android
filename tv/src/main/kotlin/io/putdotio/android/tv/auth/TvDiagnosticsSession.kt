package io.putdotio.android.tv.auth

import io.putdotio.android.telemetry.DiagnosticsSession
import io.putdotio.android.tv.TvSessionKey

/** Restoring, validating and signing out keep the last answer; the device-code screen is signed out, so on. */
internal fun TvAuthState.diagnosticsSession(): DiagnosticsSession =
    when (this) {
        TvAuthState.Initializing,
        TvAuthState.RestoringSession,
        is TvAuthState.ValidatingSession,
        is TvAuthState.ValidationUnavailable,
        TvAuthState.SigningOut,
        -> DiagnosticsSession.Unresolved

        is TvAuthState.Linking -> DiagnosticsSession.SignedOut

        is TvAuthState.SignedIn ->
            DiagnosticsSession.SignedIn(TvSessionKey(account.userId, sessionId), account.diagnosticsEnabled)
    }
