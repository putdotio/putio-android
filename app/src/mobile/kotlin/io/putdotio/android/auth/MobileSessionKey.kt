package io.putdotio.android.auth

/** One signed-in session: the same account signing in again is a new session. */
internal data class MobileSessionKey(
    val userId: Long,
    val sessionId: MobileAuthSessionId,
)

/** The signed-in session's key, or null in every other state. */
internal fun MobileAuthState.sessionKey(): MobileSessionKey? =
    (this as? MobileAuthState.SignedIn)?.let { MobileSessionKey(it.account.userId, it.sessionId) }
