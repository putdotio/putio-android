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

    data class SignedIn(
        val account: MobileAccount,
        val sessionId: MobileAuthSessionId,
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
    RETRY,
}

enum class OAuthCallbackHandlingResult {
    ACCEPTED,
    REJECTED,
}

// One mutex and one state flow serialize every auth transition; splitting the operations
// across classes would share that mutable state between them.
@Suppress("TooManyFunctions")
class MobileAuthController internal constructor(
    private val oauthConfiguration: MobileOAuthConfiguration,
    private val tokenStore: AuthTokenStore,
    private val oauthAttempts: OAuthAttempts,
    private val sessionGateway: AuthSessionGateway,
    private val tokenRevocations: TokenRevocations,
    /** Drops what this device keeps for the account, such as the Move picker's remembered folder. */
    private val clearAccountLocalState: () -> Unit = {},
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
        if (signedOutState == null) {
            return@withLock OAuthLaunchResult.NotAllowed
        }
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
                    persistAndValidateCallbackToken(callback.accessToken)
                    OAuthCallbackHandlingResult.ACCEPTED
                }
            }
        }

    suspend fun retryValidation(): Boolean = operationMutex.withLock {
        val unavailableState = mutableState.value as? MobileAuthState.ValidationUnavailable
        if (unavailableState == null) {
            return@withLock false
        }

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
            val cleared = clearLocalSession()
            mutableState.value = if (cleared) {
                MobileAuthState.SignedOut()
            } else {
                MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
            }
        }
    }

    private suspend fun persistAndValidateCallbackToken(accessToken: AccessToken) {
        try {
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
        validateConfiguredSession(SessionValidationSource.OAUTH_CALLBACK)
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
                sessionSequence = Math.incrementExact(sessionSequence)
                mutableState.value = MobileAuthState.SignedIn(
                    account = result.account,
                    sessionId = MobileAuthSessionId(sessionSequence),
                )
            }
            is SessionValidationResult.Unavailable -> mutableState.value = MobileAuthState.ValidationUnavailable(source)
            is SessionValidationResult.Rejected -> handleRejectedSession()
        }
    }

    private suspend fun handleRejectedSession() = withContext(NonCancellable) {
        val cleared = clearLocalSession()
        mutableState.value = if (cleared) {
            MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired)
        } else {
            MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
        }
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
        val storageCleared = try {
            tokenStore.clear()
            true
        } catch (_: AuthTokenStorageException) {
            false
        }
        val pendingAttemptCleared = oauthAttempts.clear()
        clearConfiguredSession()
        return storageCleared && pendingAttemptCleared
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
                mutableState.value = if (oauthAttempts.clear()) {
                    MobileAuthState.SignedOut(reason)
                } else {
                    MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
                }
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

private fun MobileOAuthConfiguration.initialSignedOutState(): MobileAuthState.SignedOut =
    MobileAuthState.SignedOut(
        reason = if (this is MobileOAuthConfiguration.Unavailable) {
            MobileSignedOutReason.OAuthNotConfigured
        } else {
            null
        },
    )

private fun MobileAuthState.canHandleOAuthAttempt(): Boolean =
    this == MobileAuthState.Initializing ||
        this == MobileAuthState.AwaitingOAuthCallback ||
        this is MobileAuthState.SignedOut && reason != MobileSignedOutReason.SecureStorageUnavailable

