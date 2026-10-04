package io.putdotio.android.tv.auth

import io.putdotio.android.auth.AccessToken
import io.putdotio.android.auth.AuthTokenStore
import io.putdotio.android.auth.TokenRevocations
import io.putdotio.sdk.auth.DeviceCodeAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@JvmInline
value class TvAuthSessionId internal constructor(
    val value: Long,
)

sealed interface TvAuthState {
    data object Initializing : TvAuthState

    data object RestoringSession : TvAuthState

    /** The device-code screen. There is no signed-out resting state on TV: a code is always in play or offered. */
    data class Linking(
        val phase: TvLinkPhase,
        val sessionExpired: Boolean = false,
    ) : TvAuthState

    data class ValidatingSession(
        val source: TvSessionValidationSource,
    ) : TvAuthState

    data class ValidationUnavailable(
        val source: TvSessionValidationSource,
    ) : TvAuthState

    data class SignedIn(
        val account: TvAccount,
        val sessionId: TvAuthSessionId,
    ) : TvAuthState

    data object SigningOut : TvAuthState
}

sealed interface TvLinkPhase {
    data object RequestingCode : TvLinkPhase

    data class AwaitingLink(
        val code: String,
    ) : TvLinkPhase

    data object Validating : TvLinkPhase

    /** Polling stopped; the user asks for a new code to continue. */
    data class Stopped(
        val reason: TvLinkStop,
    ) : TvLinkPhase
}

sealed interface TvLinkStop {
    data object CodeExpired : TvLinkStop

    data class Failed(
        val failure: TvLinkFailure,
    ) : TvLinkStop

    data object StorageUnavailable : TvLinkStop
}

enum class TvSessionValidationSource {
    RESTORE,
    RETRY,
}

/**
 * Owns the TV session: restores a stored token, imports a tv-native one when there
 * is none, otherwise drives device-code attempts through [TvDeviceLinking], and
 * persists the linked token in Keystore-backed storage.
 */
class TvAuthController internal constructor(
    tokenStore: AuthTokenStore,
    sessionGateway: TvSessionGateway,
    private val tokenRevocations: TokenRevocations,
    scope: CoroutineScope,
    private val legacySession: LegacyTvSession,
) {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<TvAuthState>(TvAuthState.Initializing)
    private var sessionSequence = 0L
    private val tokens = TvSessionTokens(tokenStore, sessionGateway)
    private val linking = TvDeviceLinking(mutableState, sessionGateway, tokens, scope, ::persistLinkedSession)

    val state: StateFlow<TvAuthState> = mutableState.asStateFlow()

    suspend fun restoreSession() = operationMutex.withLock {
        if (mutableState.value != TvAuthState.Initializing) {
            return@withLock
        }

        mutableState.value = TvAuthState.RestoringSession
        tokenRevocations.resume()
        try {
            val accessToken = when (val stored = tokens.read()) {
                is StoredToken.Present -> {
                    // Unread: a Keystore session supersedes it. Deleting here retries a
                    // cleanup that failed after import.
                    legacySession.delete()
                    stored.accessToken
                }
                StoredToken.Absent -> {
                    importLegacySession(TvSessionValidationSource.RESTORE)
                    return@withLock
                }
                StoredToken.Unreadable -> {
                    linking.stop(TvLinkStop.StorageUnavailable, sessionExpired = false)
                    return@withLock
                }
            }
            tokens.configure(accessToken)
            validateStoredSession(TvSessionValidationSource.RESTORE)
        } catch (error: CancellationException) {
            rollBackInterruptedValidation(TvAuthState.Initializing)
            throw error
        }
    }

    /**
     * Abandons the current code, if any, and requests another. Ignored while a
     * code is being validated, and after joining an attempt that was linked
     * in the meantime: an approved code wins over the request to replace it.
     */
    suspend fun requestNewCode(): Boolean = operationMutex.withLock {
        val current = mutableState.value as? TvAuthState.Linking ?: return@withLock false
        if (current.phase == TvLinkPhase.Validating) {
            return@withLock false
        }
        // The caller is a UI scope; if it dies between the join and the launch the old
        // code would stay on screen with nothing polling it.
        withContext(NonCancellable) {
            linking.cancelAndJoin()
            if (mutableState.value is TvAuthState.Linking) {
                linking.start(current.sessionExpired)
                true
            } else {
                false
            }
        }
    }

    suspend fun retryValidation(): Boolean = operationMutex.withLock {
        val unavailable = mutableState.value as? TvAuthState.ValidationUnavailable ?: return@withLock false
        try {
            val accessToken = when (val stored = tokens.read()) {
                is StoredToken.Present -> stored.accessToken
                StoredToken.Absent -> return@withLock importLegacySession(TvSessionValidationSource.RETRY)
                StoredToken.Unreadable -> {
                    linking.stop(TvLinkStop.StorageUnavailable, sessionExpired = false)
                    return@withLock false
                }
            }
            tokens.configure(accessToken)
            validateStoredSession(TvSessionValidationSource.RETRY)
            true
        } catch (error: CancellationException) {
            rollBackInterruptedValidation(unavailable)
            throw error
        }
    }

    // Only an in-flight validation is rolled back. A verdict that already settled
    // (signed in, unavailable, or expired with a link attempt running) stands, since
    // the caller's cancellation arrived after the NonCancellable work finished.
    private fun rollBackInterruptedValidation(previous: TvAuthState) {
        val current = mutableState.value
        if (current is TvAuthState.ValidatingSession || current == TvAuthState.RestoringSession) {
            tokens.clearConfigured()
            mutableState.value = previous
        }
    }

    suspend fun rejectAuthoritativeSession(expectedSessionId: TvAuthSessionId? = null): Boolean =
        operationMutex.withLock {
            val signedIn = mutableState.value as? TvAuthState.SignedIn ?: return@withLock false
            if (expectedSessionId != null && signedIn.sessionId != expectedSessionId) {
                return@withLock false
            }
            linking.restart(sessionExpired = true)
            true
        }

    /** Signs out locally without waiting for put.io; the token is revoked in the background. */
    suspend fun logout(): Unit = operationMutex.withLock {
        if (mutableState.value !is TvAuthState.SignedIn) {
            return@withLock
        }
        mutableState.value = TvAuthState.SigningOut
        withContext(NonCancellable) {
            tokens.configured?.let { tokenRevocations.revoke(it) }
            linking.restart(sessionExpired = false)
        }
    }

    // The token leaves the SDK exactly once, here; a store failure discards it rather than
    // running an unpersisted session that would vanish on the next launch.
    private suspend fun persistLinkedSession(
        linked: DeviceCodeAuthState.Linked,
        sessionExpired: Boolean,
    ) {
        val accessToken = AccessToken.parse(linked.accessToken)
        when {
            accessToken == null -> linking.stop(TvLinkStop.Failed(TvLinkFailure.SERVER), sessionExpired)
            !tokens.write(accessToken) -> linking.stop(TvLinkStop.StorageUnavailable, sessionExpired)
            !tokenRevocations.keep(accessToken) -> linking.restart(sessionExpired = true)
            else -> {
                tokens.configure(accessToken)
                signIn(linked.account.toTvAccount())
            }
        }
    }

    private suspend fun validateStoredSession(source: TvSessionValidationSource) {
        mutableState.value = TvAuthState.ValidatingSession(source)
        when (val result = tokens.validate()) {
            is TvSessionValidation.Valid -> signIn(result.account)
            is TvSessionValidation.Unavailable -> mutableState.value = TvAuthState.ValidationUnavailable(source)
            TvSessionValidation.Rejected -> linking.restart(sessionExpired = true)
        }
    }

    /**
     * Carries a tv-native session over: the token is stored only after put.io
     * accepts it, and the legacy copy is deleted once the token is stored or
     * put.io rejects it. Without a verdict (offline, put.io down) the copy stays
     * and the viewer gets Retry, which comes back here, as does the next launch;
     * a Keystore write failure also keeps it for the next launch. True when a
     * legacy token was validated.
     */
    private suspend fun importLegacySession(source: TvSessionValidationSource): Boolean {
        val legacyToken = legacySession.read()
        if (legacyToken == null) {
            withContext(NonCancellable) { legacySession.delete() }
            linking.start(sessionExpired = false)
            return false
        }
        tokens.configure(legacyToken)
        mutableState.value = TvAuthState.ValidatingSession(source)
        val result = tokens.validate()
        withContext(NonCancellable) {
            when (result) {
                is TvSessionValidation.Unavailable -> {
                    tokens.clearConfigured()
                    mutableState.value = TvAuthState.ValidationUnavailable(source)
                }
                TvSessionValidation.Rejected -> {
                    legacySession.delete()
                    tokens.clearConfigured()
                    linking.start(sessionExpired = false)
                }
                is TvSessionValidation.Valid -> {
                    if (tokens.write(legacyToken)) {
                        legacySession.delete()
                        // Like a linked token: one awaiting revocation is kept, one already revoked is not.
                        if (tokenRevocations.keep(legacyToken)) {
                            signIn(result.account)
                        } else {
                            linking.restart(sessionExpired = true)
                        }
                    } else {
                        tokens.clearConfigured()
                        linking.stop(TvLinkStop.StorageUnavailable, sessionExpired = false)
                    }
                }
            }
        }
        return true
    }

    private fun signIn(account: TvAccount) {
        sessionSequence = Math.incrementExact(sessionSequence)
        mutableState.value = TvAuthState.SignedIn(account, TvAuthSessionId(sessionSequence))
    }
}
