package io.putdotio.android.auth

import io.putdotio.android.telemetry.DiagnosticsSession

/** Restoring, validating and signing out keep the last answer; signed out, or waiting on the browser, is on. */
internal fun MobileAuthState.diagnosticsSession(): DiagnosticsSession =
    when (this) {
        MobileAuthState.Initializing,
        MobileAuthState.RestoringSession,
        is MobileAuthState.ValidatingSession,
        is MobileAuthState.ValidationUnavailable,
        MobileAuthState.SigningOut,
        -> DiagnosticsSession.Unresolved

        is MobileAuthState.SignedOut,
        MobileAuthState.AwaitingOAuthCallback,
        -> DiagnosticsSession.SignedOut

        is MobileAuthState.SignedIn ->
            DiagnosticsSession.SignedIn(MobileSessionKey(account.userId, sessionId), account.diagnosticsEnabled)
    }
