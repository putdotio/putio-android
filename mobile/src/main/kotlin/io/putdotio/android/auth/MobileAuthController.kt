package io.putdotio.android.auth

import io.putdotio.android.AccountStorage
import io.putdotio.android.account.InactiveAccountNotice
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MobileAccount(
    val userId: Long,
    val username: String,
    val email: String,
    val historyEnabled: Boolean = false,
    val avatarUrl: String? = null,
    val storage: AccountStorage = AccountStorage(),
    val inactiveNotice: InactiveAccountNotice? = null,
    /** The account's `diagnostics_enabled` at sign-in; the settings controller's answers supersede it. */
    val diagnosticsEnabled: Boolean = true,
)

@JvmInline
value class MobileAuthSessionId internal constructor(
    val value: Long,
)

sealed interface MobileAuthState {
    data object Initializing : MobileAuthState

    data object RestoringSession : MobileAuthState

    data class SignedOut(
        val reason: MobileSignedOutReason? = null,
    ) : MobileAuthState

    data object AwaitingOAuthCallback : MobileAuthState

    data class ValidatingSession(
        val source: SessionValidationSource,
    ) : MobileAuthState

    data class ValidationUnavailable(
        val source: SessionValidationSource,
    ) : MobileAuthState

    /**
     * [validated] is false while put.io has not answered for a restored session: the shell runs
     * offline on the account put.io last confirmed, and [MobileAuthController.revalidate] confirms it
     * in place, keeping [sessionId].
     */
    data class SignedIn(
        val account: MobileAccount,
        val sessionId: MobileAuthSessionId,
        val validated: Boolean = true,
    ) : MobileAuthState

    data object SigningOut : MobileAuthState
}

sealed interface MobileSignedOutReason {
    data object SessionExpired : MobileSignedOutReason

    data object SignInFailed : MobileSignedOutReason

    data object OAuthNotConfigured : MobileSignedOutReason

    data object SecureStorageUnavailable : MobileSignedOutReason
}

enum class SessionValidationSource {
    RESTORE,
    OAUTH_CALLBACK,

    /** A token from a device-code grant; only debug builds sign in this way. */
    DEVICE_LINK,
    RETRY,
}

enum class OAuthCallbackHandlingResult {
    ACCEPTED,
    REJECTED,
}

// One mutex and one state flow serialize every auth transition; splitting the operations
// across classes would share that mutable state between them. Each parameter is a separate
// store or process boundary those transitions order.
@Suppress("TooManyFunctions", "LongParameterList")
class MobileAuthController internal constructor(
    private val oauthConfiguration: MobileOAuthConfiguration,
    private val tokenStore: AuthTokenStore,
    private val oauthAttempts: OAuthAttempts,
    private val sessionGateway: AuthSessionGateway,
    private val tokenRevocations: TokenRevocations,
    /** Drops what this device keeps for the account, such as the Move picker's remembered folder. */
    private val clearAccountLocalState: () -> Unit = {},
    private val validatedAccounts: ValidatedAccountStore = NoValidatedAccounts,
) {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<MobileAuthState>(MobileAuthState.Initializing)
    private var sessionSequence = 0L

    /** The token the gateway holds, so sign-out can revoke it even when the store can't be read. */
    private var sessionToken: AccessToken? = null

    val state: StateFlow<MobileAuthState> = mutableState.asStateFlow()
    val isOAuthConfigured: Boolean = oauthConfiguration is MobileOAuthConfiguration.Configured

    suspend fun restoreSession() = operationMutex.withLock {
        if (mutableState.value != MobileAuthState.Initializing) {
            return@withLock
        }

        mutableState.value = MobileAuthState.RestoringSession
        tokenRevocations.resume()
        try {
            val accessToken = readStoredToken() ?: return@withLock
            configureSession(accessToken)
            validateConfiguredSession(SessionValidationSource.RESTORE)
        } catch (error: CancellationException) {
            clearConfiguredSession()
            mutableState.value = MobileAuthState.Initializing
            throw error
        }
    }

    suspend fun beginSignIn(): OAuthLaunchResult = operationMutex.withLock {
        val signedOutState = mutableState.value as? MobileAuthState.SignedOut
            ?: return@withLock OAuthLaunchResult.NotAllowed
        // Reset and sign in: the clear is best effort, since the new token overwrites
        // the record anyway and a store that still fails surfaces again on write.
        if (signedOutState.reason == MobileSignedOutReason.SecureStorageUnavailable) {
            withContext(NonCancellable) { clearLocalSession() }
        }

        val configuration = oauthConfiguration as? MobileOAuthConfiguration.Configured
        if (configuration == null) {
            mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.OAuthNotConfigured)
            return@withLock OAuthLaunchResult.NotConfigured
        }

        val oauthState = oauthAttempts.newState()
        val authorizationUrl = sessionGateway.buildLoginUrl(configuration.redirectUri, oauthState)
        try {
            oauthAttempts.record(oauthState)
        } catch (_: PendingOAuthAttemptStorageException) {
            mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
            return@withLock OAuthLaunchResult.StorageUnavailable
        }
        mutableState.value = MobileAuthState.AwaitingOAuthCallback
        OAuthLaunchResult.Ready(authorizationUrl)
    }

    suspend fun cancelSignIn(): Boolean = operationMutex.withLock {
        finishPendingSignIn(reason = null)
    }

    suspend fun failSignIn(): Boolean = operationMutex.withLock {
        finishPendingSignIn(MobileSignedOutReason.SignInFailed)
    }

    suspend fun handleOAuthCallback(rawCallbackUri: String?): OAuthCallbackHandlingResult =
        operationMutex.withLock {
            if (!mutableState.value.canHandleOAuthAttempt()) {
                rejectCallbackWithoutPendingAttempt()
                return@withLock OAuthCallbackHandlingResult.REJECTED
            }

            val pendingAttempt = try {
                oauthAttempts.readUnexpired()
            } catch (_: PendingOAuthAttemptStorageException) {
                oauthAttempts.clear()
                mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
                return@withLock OAuthCallbackHandlingResult.REJECTED
            }
            if (pendingAttempt == null) {
                if (!oauthAttempts.clear()) {
                    mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
                    return@withLock OAuthCallbackHandlingResult.REJECTED
                }
                rejectCallbackWithoutPendingAttempt()
                return@withLock OAuthCallbackHandlingResult.REJECTED
            }

            val callback = OAuthCallbackParser.parse(rawCallbackUri, pendingAttempt.state)
            if (
                callback is OAuthCallbackParseResult.Failure &&
                callback.reason == OAuthCallbackFailure.StateMismatch
            ) {
                mutableState.value = MobileAuthState.AwaitingOAuthCallback
                return@withLock OAuthCallbackHandlingResult.REJECTED
            }
            if (!oauthAttempts.clear()) {
                mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
                return@withLock OAuthCallbackHandlingResult.REJECTED
            }

            when (callback) {
                is OAuthCallbackParseResult.Failure -> {
                    mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed)
                    OAuthCallbackHandlingResult.REJECTED
                }

                is OAuthCallbackParseResult.Success -> {
                    persistAndValidateToken(callback.accessToken, SessionValidationSource.OAUTH_CALLBACK)
                    OAuthCallbackHandlingResult.ACCEPTED
                }
            }
        }

    /**
     * Signs in with a token a device-code grant produced, through the same storage and validation as
     * an OAuth callback. Only the debug build's device-code sign-in calls this. False unless signed out
     * with no browser sign-in pending, including one an earlier process started.
     */
    internal suspend fun completeDeviceLink(accessToken: AccessToken): Boolean = operationMutex.withLock {
        val signedOutState = mutableState.value as? MobileAuthState.SignedOut ?: return@withLock false
        val browserPending = try {
            oauthAttempts.readUnexpired() != null
        } catch (_: PendingOAuthAttemptStorageException) {
            true
        }
        if (browserPending) return@withLock false
        withContext(NonCancellable) {
            if (signedOutState.reason == MobileSignedOutReason.SecureStorageUnavailable) clearLocalSession()
            persistAndValidateToken(accessToken, SessionValidationSource.DEVICE_LINK)
        }
        true
    }

    suspend fun retryValidation(): Boolean = operationMutex.withLock {
        val unavailableState = mutableState.value as? MobileAuthState.ValidationUnavailable
            ?: return@withLock false
        try {
            val accessToken = readStoredToken() ?: return@withLock false
            configureSession(accessToken)
            validateConfiguredSession(SessionValidationSource.RETRY)
            true
        } catch (error: CancellationException) {
            clearConfiguredSession()
            mutableState.value = unavailableState
            throw error
        }
    }

    /**
     * Asks put.io again about a session that opened offline. True once nothing is left to confirm:
     * put.io confirmed or rejected it, or another state replaced it. The network call runs outside
     * the lock, so sign-out never waits on it; a result for a replaced session is dropped.
     */
    // Gateway implementations are process boundaries; cancellation remains control flow.
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun revalidate(): Boolean {
        val pending = (mutableState.value as? MobileAuthState.SignedIn)?.takeUnless { it.validated } ?: return true
        val result = try {
            sessionGateway.validateSession()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SessionValidationResult.Unavailable(error)
        }
        return operationMutex.withLock {
            val current = mutableState.value as? MobileAuthState.SignedIn
            if (current == null || current.validated || current.sessionId != pending.sessionId) {
                return@withLock true
            }
            when (result) {
                is SessionValidationResult.Valid -> {
                    validatedAccounts.write(result.account)
                    // The token names its account; a different one is a different session.
                    val sessionId = if (result.account.userId == current.account.userId) {
                        current.sessionId
                    } else {
                        nextSessionId()
                    }
                    mutableState.value = MobileAuthState.SignedIn(result.account, sessionId)
                    true
                }
                is SessionValidationResult.Rejected -> {
                    handleRejectedSession()
                    true
                }
                is SessionValidationResult.Unavailable -> false
            }
        }
    }

    suspend fun rejectAuthoritativeSession(): Boolean = rejectAuthoritativeSession(expectedSessionId = null)

    internal suspend fun rejectAuthoritativeSession(
        expectedSessionId: MobileAuthSessionId?,
    ): Boolean = operationMutex.withLock {
        val signedIn = mutableState.value as? MobileAuthState.SignedIn
        if (signedIn == null || expectedSessionId != null && signedIn.sessionId != expectedSessionId) {
            return@withLock false
        }

        handleRejectedSession()
        true
    }

    /** Signs out locally without waiting for put.io; the token is revoked in the background. */
    suspend fun logout(): Unit = operationMutex.withLock {
        mutableState.value = MobileAuthState.SigningOut
        withContext(NonCancellable) {
            (sessionToken ?: storedTokenOrNull())?.let { tokenRevocations.revoke(it) }
            mutableState.value = signedOutAfterClear(clearLocalSession(), reason = null)
        }
    }

    private suspend fun persistAndValidateToken(accessToken: AccessToken, source: SessionValidationSource) {
        try {
            // The previous token's account must never open the shell for this one.
            if (!validatedAccounts.clear()) throw AuthTokenStorageException("clear validated account")
            tokenStore.write(accessToken)
        } catch (_: AuthTokenStorageException) {
            clearConfiguredSession()
            mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
            return
        }

        if (!tokenRevocations.keep(accessToken)) {
            handleRejectedSession()
            return
        }
        configureSession(accessToken)
        validateConfiguredSession(source)
    }

    // Gateway implementations are process boundaries; cancellation remains control flow.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun validateConfiguredSession(source: SessionValidationSource) {
        mutableState.value = MobileAuthState.ValidatingSession(source)
        val result = try {
            sessionGateway.validateSession()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SessionValidationResult.Unavailable(error)
        }
        when (result) {
            is SessionValidationResult.Valid -> {
                validatedAccounts.write(result.account)
                mutableState.value = MobileAuthState.SignedIn(account = result.account, sessionId = nextSessionId())
            }
            is SessionValidationResult.Unavailable -> {
                // Only a stored session put.io confirmed before opens offline; a new sign-in has none.
                val offlineAccount = if (source.restoresStoredToken()) validatedAccounts.read() else null
                mutableState.value = if (offlineAccount != null) {
                    MobileAuthState.SignedIn(offlineAccount, nextSessionId(), validated = false)
                } else {
                    MobileAuthState.ValidationUnavailable(source)
                }
            }
            is SessionValidationResult.Rejected -> handleRejectedSession()
        }
    }

    private fun nextSessionId(): MobileAuthSessionId {
        sessionSequence = Math.incrementExact(sessionSequence)
        return MobileAuthSessionId(sessionSequence)
    }

    private suspend fun handleRejectedSession() = withContext(NonCancellable) {
        mutableState.value = signedOutAfterClear(clearLocalSession(), MobileSignedOutReason.SessionExpired)
    }

    private suspend fun readStoredToken(): AccessToken? =
        try {
            val accessToken = tokenStore.read()
            if (accessToken == null) {
                clearConfiguredSession()
                mutableState.value = oauthConfiguration.initialSignedOutState()
            }
            accessToken
        } catch (_: AuthTokenStorageException) {
            clearConfiguredSession()
            mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
            null
        }

    private fun configureSession(accessToken: AccessToken) {
        sessionToken = accessToken
        sessionGateway.setAccessToken(accessToken)
    }

    private fun clearConfiguredSession() {
        sessionToken = null
        sessionGateway.clearAccessToken()
    }

    private suspend fun storedTokenOrNull(): AccessToken? =
        try {
            tokenStore.read()
        } catch (_: AuthTokenStorageException) {
            null
        }

    private suspend fun clearLocalSession(): Boolean {
        clearAccountLocalState()
        val accountCleared = validatedAccounts.clear()
        val storageCleared = try {
            tokenStore.clear()
            true
        } catch (_: AuthTokenStorageException) {
            false
        }
        val pendingAttemptCleared = oauthAttempts.clear()
        clearConfiguredSession()
        return accountCleared && storageCleared && pendingAttemptCleared
    }

    private suspend fun finishPendingSignIn(reason: MobileSignedOutReason?): Boolean {
        val currentState = mutableState.value
        if (!currentState.canHandleOAuthAttempt()) {
            return false
        }

        return try {
            val hasPendingAttempt = currentState == MobileAuthState.AwaitingOAuthCallback ||
                oauthAttempts.exists()
            if (hasPendingAttempt) {
                mutableState.value = signedOutAfterClear(oauthAttempts.clear(), reason)
            }
            hasPendingAttempt
        } catch (_: PendingOAuthAttemptStorageException) {
            mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
            true
        }
    }

    private fun rejectCallbackWithoutPendingAttempt() {
        val currentState = mutableState.value
        if (
            currentState == MobileAuthState.AwaitingOAuthCallback ||
            currentState is MobileAuthState.SignedOut &&
            currentState.reason != MobileSignedOutReason.SecureStorageUnavailable
        ) {
            mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed)
        }
    }
}

private fun signedOutAfterClear(cleared: Boolean, reason: MobileSignedOutReason?): MobileAuthState.SignedOut =
    MobileAuthState.SignedOut(if (cleared) reason else MobileSignedOutReason.SecureStorageUnavailable)

private fun MobileOAuthConfiguration.initialSignedOutState(): MobileAuthState.SignedOut =
    MobileAuthState.SignedOut(
        reason = if (this is MobileOAuthConfiguration.Unavailable) {
            MobileSignedOutReason.OAuthNotConfigured
        } else {
            null
        },
    )

private fun SessionValidationSource.restoresStoredToken(): Boolean =
    this == SessionValidationSource.RESTORE || this == SessionValidationSource.RETRY

private fun MobileAuthState.canHandleOAuthAttempt(): Boolean =
    this == MobileAuthState.Initializing ||
        this == MobileAuthState.AwaitingOAuthCallback ||
        this is MobileAuthState.SignedOut && reason != MobileSignedOutReason.SecureStorageUnavailable

