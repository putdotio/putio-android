package io.putdotio.android.tv.auth

import io.putdotio.android.auth.AccessToken
import io.putdotio.android.auth.AuthTokenStorageException
import io.putdotio.android.auth.AuthTokenStore
import io.putdotio.sdk.auth.DeviceCodeAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * Owns the TV session: restores a stored token, otherwise drives one device-code
 * attempt at a time through the SDK orchestrator, and persists the linked token
 * in Keystore-backed storage. One attempt runs on [scope]; [requestNewCode]
 * replaces it.
 */
class TvAuthController internal constructor(
    private val tokenStore: AuthTokenStore,
    private val sessionGateway: TvSessionGateway,
    private val scope: CoroutineScope,
) {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<TvAuthState>(TvAuthState.Initializing)
    private var sessionSequence = 0L
    private var linkAttempt: Job? = null

    val state: StateFlow<TvAuthState> = mutableState.asStateFlow()

    suspend fun restoreSession() = operationMutex.withLock {
        if (mutableState.value != TvAuthState.Initializing) {
            return@withLock
        }

        mutableState.value = TvAuthState.RestoringSession
        try {
            val accessToken = readStoredToken()
            if (accessToken == null) {
                startLinkAttempt(sessionExpired = false)
                return@withLock
            }
            sessionGateway.setAccessToken(accessToken)
            validateStoredSession(TvSessionValidationSource.RESTORE)
        } catch (error: CancellationException) {
            sessionGateway.clearAccessToken()
            mutableState.value = TvAuthState.Initializing
            throw error
        }
    }

    /** Abandons the current code, if any, and requests another. Ignored while a code is being validated. */
    suspend fun requestNewCode(): Boolean = operationMutex.withLock {
        val linking = mutableState.value as? TvAuthState.Linking ?: return@withLock false
        if (linking.phase == TvLinkPhase.Validating) {
            return@withLock false
        }
        linkAttempt?.cancel()
        startLinkAttempt(linking.sessionExpired)
        true
    }

    suspend fun retryValidation(): Boolean = operationMutex.withLock {
        val unavailable = mutableState.value as? TvAuthState.ValidationUnavailable ?: return@withLock false
        try {
            val accessToken = readStoredToken()
            if (accessToken == null) {
                startLinkAttempt(sessionExpired = false)
                return@withLock false
            }
            sessionGateway.setAccessToken(accessToken)
            validateStoredSession(TvSessionValidationSource.RETRY)
            true
        } catch (error: CancellationException) {
            sessionGateway.clearAccessToken()
            mutableState.value = unavailable
            throw error
        }
    }

    suspend fun rejectAuthoritativeSession(expectedSessionId: TvAuthSessionId? = null): Boolean =
        operationMutex.withLock {
            val signedIn = mutableState.value as? TvAuthState.SignedIn ?: return@withLock false
            if (expectedSessionId != null && signedIn.sessionId != expectedSessionId) {
                return@withLock false
            }
            expireSession()
            true
        }

    suspend fun logout(): Unit = operationMutex.withLock {
        if (mutableState.value !is TvAuthState.SignedIn) {
            return@withLock
        }
        mutableState.value = TvAuthState.SigningOut
        try {
            sessionGateway.logout()
        } finally {
            withContext(NonCancellable) {
                val cleared = clearLocalSession()
                startLinkAttempt(sessionExpired = false, storageCleared = cleared)
            }
        }
    }

    private fun startLinkAttempt(
        sessionExpired: Boolean,
        storageCleared: Boolean = true,
    ) {
        if (!storageCleared) {
            mutableState.value = TvAuthState.Linking(TvLinkPhase.Stopped(TvLinkStop.StorageUnavailable), sessionExpired)
            return
        }
        mutableState.value = TvAuthState.Linking(TvLinkPhase.RequestingCode, sessionExpired)
        linkAttempt = scope.launch {
            sessionGateway.link().collect { linkState -> onLinkState(linkState, sessionExpired) }
        }
    }

    private suspend fun onLinkState(
        linkState: DeviceCodeAuthState,
        sessionExpired: Boolean,
    ) {
        when (linkState) {
            DeviceCodeAuthState.Requesting -> Unit
            is DeviceCodeAuthState.AwaitingLink ->
                mutableState.value = TvAuthState.Linking(TvLinkPhase.AwaitingLink(linkState.code), sessionExpired)
            DeviceCodeAuthState.Validating ->
                mutableState.value = TvAuthState.Linking(TvLinkPhase.Validating, sessionExpired)
            is DeviceCodeAuthState.Linked -> withContext(NonCancellable) { persistLinkedSession(linkState) }
            is DeviceCodeAuthState.Expired -> stopLinking(TvLinkStop.CodeExpired, sessionExpired)
            is DeviceCodeAuthState.Failed ->
                stopLinking(TvLinkStop.Failed(linkState.error.toTvLinkFailure()), sessionExpired)
        }
    }

    // The token leaves the SDK exactly once, here; a store failure discards it rather than
    // running an unpersisted session that would vanish on the next launch.
    private suspend fun persistLinkedSession(linked: DeviceCodeAuthState.Linked) {
        val accessToken = AccessToken.parse(linked.accessToken)
        if (accessToken == null) {
            stopLinking(TvLinkStop.Failed(TvLinkFailure.SERVER), sessionExpired = false)
            return
        }
        try {
            tokenStore.write(accessToken)
        } catch (_: AuthTokenStorageException) {
            stopLinking(TvLinkStop.StorageUnavailable, sessionExpired = false)
            return
        }
        sessionGateway.setAccessToken(accessToken)
        signIn(linked.account.toTvAccount())
    }

    private fun stopLinking(
        reason: TvLinkStop,
        sessionExpired: Boolean,
    ) {
        mutableState.value = TvAuthState.Linking(TvLinkPhase.Stopped(reason), sessionExpired)
    }

    // Gateway implementations are process boundaries; cancellation remains control flow.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun validateStoredSession(source: TvSessionValidationSource) {
        mutableState.value = TvAuthState.ValidatingSession(source)
        val result = try {
            sessionGateway.validateSession()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            TvSessionValidation.Unavailable(error)
        }
        when (result) {
            is TvSessionValidation.Valid -> signIn(result.account)
            is TvSessionValidation.Unavailable -> mutableState.value = TvAuthState.ValidationUnavailable(source)
            TvSessionValidation.Rejected -> expireSession()
        }
    }

    private fun signIn(account: TvAccount) {
        sessionSequence = Math.incrementExact(sessionSequence)
        mutableState.value = TvAuthState.SignedIn(account, TvAuthSessionId(sessionSequence))
    }

    private suspend fun expireSession() = withContext(NonCancellable) {
        val cleared = clearLocalSession()
        startLinkAttempt(sessionExpired = true, storageCleared = cleared)
    }

    private suspend fun readStoredToken(): AccessToken? =
        try {
            tokenStore.read()
        } catch (_: AuthTokenStorageException) {
            null
        }

    private suspend fun clearLocalSession(): Boolean {
        sessionGateway.clearAccessToken()
        return try {
            tokenStore.clear()
            true
        } catch (_: AuthTokenStorageException) {
            false
        }
    }
}
