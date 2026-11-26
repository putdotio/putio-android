package io.putdotio.android.auth

internal data class MobileSessionKey(
    val userId: Long,
    val sessionId: MobileAuthSessionId,
)

internal fun MobileAuthState.sessionKey(): MobileSessionKey? =
    (this as? MobileAuthState.SignedIn)?.let { MobileSessionKey(it.account.userId, it.sessionId) }

internal fun MobileAuthState.isSettled(): Boolean =
    when (this) {
        MobileAuthState.Initializing,
        MobileAuthState.RestoringSession,
        is MobileAuthState.ValidatingSession,
        MobileAuthState.SigningOut,
        -> false
        else -> true
    }
