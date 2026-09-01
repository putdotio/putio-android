package io.putdotio.android.auth

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
    val avatarUrl: String? = null,
    val storage: MobileAccountStorage = MobileAccountStorage(),
)

data class MobileAccountStorage(
    val availableBytes: Long = 0L,
    val sizeBytes: Long = 0L,
    val usedBytes: Long = 0L,
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

class MobileAuthController internal constructor(
    private val oauthConfiguration: MobileOAuthConfiguration,
    private val tokenStore: AuthTokenStore,
    private val pendingOAuthAttemptStore: PendingOAuthAttemptStore,
    private val sessionGateway: AuthSessionGateway,
    private val stateGenerator: OAuthStateGenerator = SecureOAuthStateGenerator(),
    private val clock: OAuthAttemptClock = SystemOAuthAttemptClock,
) {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<MobileAuthState>(MobileAuthState.Initializing)
    private var sessionSequence = 0L

    val state: StateFlow<MobileAuthState> = mutableState.asStateFlow()
    val isOAuthConfigured: Boolean = oauthConfiguration is MobileOAuthConfiguration.Configured

    suspend fun restoreSession() = operationMutex.withLock {
        if (mutableState.value != MobileAuthState.Initializing) {
            return@withLock
        }

        mutableState.value = MobileAuthState.RestoringSession
        try {
            val accessToken = readStoredToken() ?: return@withLock
            sessionGateway.setAccessToken(accessToken)
            validateConfiguredSession(SessionValidationSource.RESTORE)
        } catch (error: CancellationException) {
            sessionGateway.clearAccessToken()
            mutableState.value = MobileAuthState.Initializing
            throw error
        }
    }

    suspend fun beginSignIn(): OAuthLaunchResult = operationMutex.withLock {
        if (mutableState.value !is MobileAuthState.SignedOut) {
            return@withLock OAuthLaunchResult.NotAllowed
        }

        val configuration = oauthConfiguration as? MobileOAuthConfiguration.Configured
        if (configuration == null) {
            mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.OAuthNotConfigured)
            return@withLock OAuthLaunchResult.NotConfigured
        }

        val oauthState = stateGenerator.generate()
        val authorizationUrl = sessionGateway.buildLoginUrl(configuration.redirectUri, oauthState)
        try {
            pendingOAuthAttemptStore.write(
                PendingOAuthAttempt(
                    state = oauthState,
                    createdAtEpochMillis = clock.nowEpochMillis(),
                ),
            )
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
            if (!mutableState.value.canReceiveOAuthCallback()) {
                rejectCallbackWithoutPendingAttempt()
                return@withLock OAuthCallbackHandlingResult.REJECTED
            }

            val pendingAttempt = try {
                pendingOAuthAttemptStore.read()
            } catch (_: PendingOAuthAttemptStorageException) {
                clearPendingOAuthAttempt()
                mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
                return@withLock OAuthCallbackHandlingResult.REJECTED
            }
            if (pendingAttempt == null || pendingAttempt.isExpired(clock.nowEpochMillis())) {
                if (!clearPendingOAuthAttempt()) {
                    mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
                    return@withLock OAuthCallbackHandlingResult.REJECTED
                }
                rejectCallbackWithoutPendingAttempt()
                return@withLock OAuthCallbackHandlingResult.REJECTED
            }

            val callback = OAuthCallbackParser.parse(rawCallbackUri, pendingAttempt.state)
            if (!clearPendingOAuthAttempt()) {
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
            sessionGateway.setAccessToken(accessToken)
            validateConfiguredSession(SessionValidationSource.RETRY)
            true
        } catch (error: CancellationException) {
            sessionGateway.clearAccessToken()
            mutableState.value = unavailableState
            throw error
        }
    }

    suspend fun rejectAuthoritativeSession(): Boolean = operationMutex.withLock {
        if (mutableState.value !is MobileAuthState.SignedIn) {
            return@withLock false
        }

        handleRejectedSession()
        true
    }

    suspend fun logout(): Unit = operationMutex.withLock {
        mutableState.value = MobileAuthState.SigningOut
        try {
            sessionGateway.logout()
        } finally {
            val cleared = withContext(NonCancellable) { clearLocalSession() }
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
            sessionGateway.clearAccessToken()
            mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
            return
        }

        sessionGateway.setAccessToken(accessToken)
        validateConfiguredSession(SessionValidationSource.OAUTH_CALLBACK)
    }

    private suspend fun validateConfiguredSession(source: SessionValidationSource) {
        mutableState.value = MobileAuthState.ValidatingSession(source)
        when (val result = sessionGateway.validateSession()) {
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
                sessionGateway.clearAccessToken()
                mutableState.value = oauthConfiguration.initialSignedOutState()
            }
            accessToken
        } catch (_: AuthTokenStorageException) {
            sessionGateway.clearAccessToken()
            mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
            null
        }

    private suspend fun clearLocalSession(): Boolean {
        val storageCleared = try {
            tokenStore.clear()
            true
        } catch (_: AuthTokenStorageException) {
            false
        }
        val pendingAttemptCleared = clearPendingOAuthAttempt()
        sessionGateway.clearAccessToken()
        return storageCleared && pendingAttemptCleared
    }

    private suspend fun finishPendingSignIn(reason: MobileSignedOutReason?): Boolean {
        val currentState = mutableState.value
        if (!currentState.canFinishOAuthAttempt()) {
            return false
        }

        if (currentState != MobileAuthState.AwaitingOAuthCallback) {
            val persistedAttempt = try {
                pendingOAuthAttemptStore.read()
            } catch (_: PendingOAuthAttemptStorageException) {
                mutableState.value = MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
                return true
            }
            if (persistedAttempt == null) {
                return false
            }
        }

        mutableState.value = if (clearPendingOAuthAttempt()) {
            MobileAuthState.SignedOut(reason)
        } else {
            MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable)
        }
        return true
    }

    private suspend fun clearPendingOAuthAttempt(): Boolean =
        try {
            pendingOAuthAttemptStore.clear()
            true
        } catch (_: PendingOAuthAttemptStorageException) {
            false
        }

    private fun rejectCallbackWithoutPendingAttempt() {
        if (
            mutableState.value == MobileAuthState.AwaitingOAuthCallback ||
            mutableState.value is MobileAuthState.SignedOut
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

private fun MobileAuthState.canReceiveOAuthCallback(): Boolean =
    this == MobileAuthState.Initializing ||
        this == MobileAuthState.AwaitingOAuthCallback ||
        this is MobileAuthState.SignedOut

private fun MobileAuthState.canFinishOAuthAttempt(): Boolean =
    this == MobileAuthState.Initializing ||
        this == MobileAuthState.AwaitingOAuthCallback ||
        this is MobileAuthState.SignedOut

private fun PendingOAuthAttempt.isExpired(nowEpochMillis: Long): Boolean {
    val age = nowEpochMillis - createdAtEpochMillis
    return age < 0 || age > OAUTH_ATTEMPT_MAX_AGE_MILLIS
}

private const val OAUTH_ATTEMPT_MAX_AGE_MILLIS = 15 * 60 * 1_000L
