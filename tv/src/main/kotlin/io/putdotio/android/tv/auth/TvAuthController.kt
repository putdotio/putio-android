package io.putdotio.android.tv.auth

import io.putdotio.android.auth.AccessToken
import io.putdotio.android.auth.AuthTokenStorageException
import io.putdotio.android.auth.AuthTokenStore
import io.putdotio.android.auth.TokenRevocations
import io.putdotio.sdk.auth.DeviceCodeAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@JvmInline
value class TvAuthSessionId internal constructor(
    val value: Long,
)

internal sealed interface TvAuthState {
    data object Initializing : TvAuthState

    data object RestoringSession : TvAuthState

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

internal sealed interface TvLinkPhase {
    data object RequestingCode : TvLinkPhase

    data class AwaitingLink(
        val code: String,
    ) : TvLinkPhase

    data class Validating(
        val code: String?,
    ) : TvLinkPhase

    data class Stopped(
        val reason: TvLinkStop,
        val code: String? = null,
    ) : TvLinkPhase
}

internal sealed interface TvLinkStop {
    data object CodeExpired : TvLinkStop

    data class Failed(
        val failure: TvLinkFailure,
    ) : TvLinkStop

    data object StorageUnavailable : TvLinkStop

    data object SessionUnreadable : TvLinkStop
}

internal val TvLinkStop.isStorageFailure: Boolean
    get() = this == TvLinkStop.StorageUnavailable || this == TvLinkStop.SessionUnreadable

internal enum class TvSessionValidationSource {
    RESTORE,
    RETRY,
}

internal class TvAuthController(
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
    private val mutableQuietSignOuts = MutableStateFlow(0)

    val state: StateFlow<TvAuthState> = mutableState.asStateFlow()

    val quietSignOuts: StateFlow<Int> = mutableQuietSignOuts.asStateFlow()

    suspend fun restoreSession(interactive: Boolean = true): Boolean = operationMutex.withLock {
        if (mutableState.value != TvAuthState.Initializing) {
            return@withLock mutableState.value is TvAuthState.SignedIn
        }
        val quietToken = if (interactive) {
            null
        } else {
            tokens.readSession(tokenRevocations) as? StoredToken.Present ?: return@withLock false
        }

        mutableState.value = TvAuthState.RestoringSession
        tokenRevocations.resume()
        try {
            val accessToken = when (val stored = quietToken ?: tokens.readSession(tokenRevocations)) {
                is StoredToken.Present -> {
                    legacySession.delete()
                    stored.accessToken
                }
                StoredToken.Absent -> {
                    importLegacySession(TvSessionValidationSource.RESTORE)
                    return@withLock mutableState.value is TvAuthState.SignedIn
                }
                StoredToken.SignedOut -> {
                    linking.restart(sessionExpired = false)
                    return@withLock false
                }
                StoredToken.Unreadable -> {
                    linking.stop(TvLinkStop.SessionUnreadable, sessionExpired = false)
                    return@withLock false
                }
            }
            tokens.configure(accessToken)
            validateStoredSession(TvSessionValidationSource.RESTORE, quiet = !interactive)
        } catch (error: CancellationException) {
            rollBackInterruptedValidation(TvAuthState.Initializing)
            throw error
        }
        mutableState.value is TvAuthState.SignedIn
    }

    suspend fun requestNewCode(): Boolean = operationMutex.withLock {
        val current = mutableState.value as? TvAuthState.Linking ?: return@withLock false
        if (current.phase is TvLinkPhase.Validating) {
            return@withLock false
        }
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
        val previous = mutableState.value.takeIf { it.retriesStoredSession() } ?: return@withLock false
        try {
            val accessToken = when (val stored = tokens.readSession(tokenRevocations)) {
                is StoredToken.Present -> stored.accessToken
                StoredToken.Absent -> return@withLock importLegacySession(TvSessionValidationSource.RETRY)
                StoredToken.SignedOut -> {
                    linking.restart(sessionExpired = false)
                    return@withLock false
                }
                StoredToken.Unreadable -> {
                    linking.stop(TvLinkStop.SessionUnreadable, sessionExpired = false)
                    return@withLock false
                }
            }
            tokens.configure(accessToken)
            validateStoredSession(TvSessionValidationSource.RETRY)
            true
        } catch (error: CancellationException) {
            rollBackInterruptedValidation(previous)
            throw error
        }
    }

    private fun rollBackInterruptedValidation(previous: TvAuthState) {
        val current = mutableState.value
        if (current is TvAuthState.ValidatingSession || current == TvAuthState.RestoringSession) {
            tokens.clearConfigured()
            mutableState.value = previous
        }
    }

    suspend fun rejectAuthoritativeSession(
        expectedSessionId: TvAuthSessionId? = null,
        quiet: Boolean = false,
    ): Boolean =
        operationMutex.withLock {
            val signedIn = mutableState.value as? TvAuthState.SignedIn ?: return@withLock false
            if (expectedSessionId != null && signedIn.sessionId != expectedSessionId) {
                return@withLock false
            }
            if (quiet) {
                withContext(NonCancellable) { tokens.signOutQuietly(mutableState, mutableQuietSignOuts) }
            } else {
                linking.restart(sessionExpired = true)
            }
            true
        }

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

    private suspend fun persistLinkedSession(
        linked: DeviceCodeAuthState.Linked,
        sessionExpired: Boolean,
    ) {
        val accessToken = AccessToken.parse(linked.accessToken)
        if (accessToken == null) {
            linking.stop(TvLinkStop.Failed(TvLinkFailure.SERVER), sessionExpired)
            return
        }
        when (tokenRevocations.keepStored(accessToken, tokens)) {
            false -> linking.restart(sessionExpired = true)
            true -> {
                tokens.configure(accessToken)
                signIn(linked.account.toTvAccount())
            }
            null -> linking.stop(TvLinkStop.StorageUnavailable, sessionExpired)
        }
    }

    private suspend fun validateStoredSession(source: TvSessionValidationSource, quiet: Boolean = false) {
        mutableState.value = TvAuthState.ValidatingSession(source)
        val result = tokens.validate()
        when {
            result is TvSessionValidation.Valid -> signIn(result.account)
            quiet && result is TvSessionValidation.Unavailable -> {
                tokens.clearConfigured()
                mutableState.value = TvAuthState.Initializing
            }
            quiet -> withContext(NonCancellable) { tokens.signOutQuietly(mutableState, mutableQuietSignOuts) }
            result is TvSessionValidation.Unavailable -> mutableState.value = TvAuthState.ValidationUnavailable(source)
            else -> linking.restart(sessionExpired = true)
        }
    }

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
                    when (tokenRevocations.keepStored(legacyToken, tokens)) {
                        false -> {
                            legacySession.delete()
                            linking.restart(sessionExpired = true)
                        }
                        true -> {
                            legacySession.delete()
                            signIn(result.account)
                        }
                        null -> {
                            tokens.clearConfigured()
                            linking.stop(TvLinkStop.StorageUnavailable, sessionExpired = false)
                        }
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

private fun TvAuthState.retriesStoredSession(): Boolean =
    this is TvAuthState.ValidationUnavailable ||
        this is TvAuthState.Linking && (phase as? TvLinkPhase.Stopped)?.reason == TvLinkStop.SessionUnreadable

private suspend fun TvSessionTokens.signOutQuietly(
    state: MutableStateFlow<TvAuthState>,
    signOuts: MutableStateFlow<Int>,
) {
    clear()
    state.value = TvAuthState.Initializing
    signOuts.update { it + 1 }
}

private suspend fun TvSessionTokens.readSession(revocations: TokenRevocations): StoredToken {
    val stored = read()
    if (stored !is StoredToken.Present) return stored
    return try {
        if (revocations.isSignedOut(stored.accessToken)) StoredToken.SignedOut else stored
    } catch (_: AuthTokenStorageException) {
        StoredToken.Unreadable
    }
}

private suspend fun TokenRevocations.keepStored(accessToken: AccessToken, tokens: TvSessionTokens): Boolean? =
    try {
        keep(accessToken) {
            if (!tokens.write(accessToken)) throw AuthTokenStorageException("write session")
        }
    } catch (_: AuthTokenStorageException) {
        null
    }
