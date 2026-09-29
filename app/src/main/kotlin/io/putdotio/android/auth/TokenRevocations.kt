package io.putdotio.android.auth

import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal enum class TokenRevocationResult {
    /** put.io deleted the token. */
    REVOKED,

    /** put.io no longer accepts the token, so there is nothing left to revoke. */
    REJECTED,

    /** No verdict (network, server, or unexpected failure); the token may still be valid. */
    UNAVAILABLE,
}

internal fun interface AuthTokenRevoker {
    suspend fun revoke(accessToken: AccessToken): TokenRevocationResult
}

/**
 * Revokes a specific token with its own short-lived client, so a retry never
 * touches the live session's client or revokes whatever token it holds now.
 */
internal class PutioAuthTokenRevoker(
    private val sessionConfig: PutioConfig,
) : AuthTokenRevoker {
    // The SDK is a process boundary; any failure other than cancellation is "no verdict yet".
    @Suppress("TooGenericExceptionCaught")
    override suspend fun revoke(accessToken: AccessToken): TokenRevocationResult =
        try {
            PutioClient(sessionConfig.copy(accessToken = accessToken.reveal())).use { client ->
                client.auth.logout()
            }
            TokenRevocationResult.REVOKED
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error.isAuthoritativeAuthRejection()) {
                TokenRevocationResult.REJECTED
            } else {
                TokenRevocationResult.UNAVAILABLE
            }
        }
}

/** The controller's view of revocations that outlive a sign-out. */
internal interface TokenRevocations {
    /** Remembers [accessToken] until put.io confirms it is gone, and starts revoking it in the background. */
    suspend fun revoke(accessToken: AccessToken)

    /** Drops a pending revocation of [accessToken]: put.io handed the same token back on a new sign-in. */
    suspend fun keep(accessToken: AccessToken)

    /**
     * Retries a revocation left unconfirmed by an earlier attempt or process, unless the
     * signed-in session holds that token again (a [keep] that crashed or failed to clear).
     */
    fun resume()
}

/**
 * Sign-out is local and immediate; the remote revocation is retried here with
 * bounded backoff until put.io confirms it or rejects the token. The token is
 * kept only in [store] (Keystore-encrypted) and in memory, so a storage failure
 * still retries for the life of the process. Each trigger ([revoke], [resume])
 * runs at most [retryDelays].size attempts; an unconfirmed token waits for the
 * next trigger (app start or sign-out). One slot: a newer sign-out replaces an
 * older unconfirmed token. Revocation is idempotent: a token put.io already
 * dropped answers 401, which also clears the slot.
 */
internal class PendingTokenRevocations(
    private val store: AuthTokenStore,
    private val sessionStore: AuthTokenStore,
    private val revoker: AuthTokenRevoker,
    private val scope: CoroutineScope,
    private val retryDelays: List<Duration> = DEFAULT_REVOCATION_RETRY_DELAYS,
) : TokenRevocations {
    private val slotMutex = Mutex()
    private var pending: AccessToken? = null
    private var storeLoaded = false
    private val jobLock = Any()
    private var retryJob: Job? = null

    override suspend fun revoke(accessToken: AccessToken) {
        slotMutex.withLock {
            pending = accessToken
            storeLoaded = true
            try {
                store.write(accessToken)
            } catch (_: AuthTokenStorageException) {
                // The in-memory copy still retries until this process ends.
            }
        }
        restartRetries()
    }

    override suspend fun keep(accessToken: AccessToken) {
        slotMutex.withLock {
            if (loadPending()?.reveal() == accessToken.reveal()) {
                clearSlot()
            }
        }
    }

    override fun resume() {
        synchronized(jobLock) {
            if (retryJob?.isActive != true) {
                retryJob = scope.launch {
                    dropIfSessionToken()
                    retry()
                }
            }
        }
    }

    private suspend fun dropIfSessionToken() {
        slotMutex.withLock {
            val accessToken = loadPending() ?: return
            val sessionToken = try {
                sessionStore.read()
            } catch (_: AuthTokenStorageException) {
                // An unreadable session cannot be restored, so its token is not in use.
                null
            }
            if (sessionToken?.reveal() == accessToken.reveal()) clearSlot()
        }
    }

    private fun restartRetries() {
        synchronized(jobLock) {
            retryJob?.cancel()
            retryJob = scope.launch { retry() }
        }
    }

    private suspend fun retry() {
        for (retryDelay in retryDelays) {
            delay(retryDelay)
            val accessToken = slotMutex.withLock { loadPending() } ?: return
            when (revoker.revoke(accessToken)) {
                TokenRevocationResult.REVOKED,
                TokenRevocationResult.REJECTED,
                -> {
                    slotMutex.withLock {
                        if (pending === accessToken) clearSlot()
                    }
                    return
                }
                TokenRevocationResult.UNAVAILABLE -> Unit
            }
        }
    }

    // Call with slotMutex held.
    private suspend fun loadPending(): AccessToken? {
        if (!storeLoaded) {
            pending = try {
                store.read()
            } catch (_: AuthTokenStorageException) {
                null
            }
            storeLoaded = true
        }
        return pending
    }

    // Call with slotMutex held. A record left by a failed clear is either revoked (401 if put.io
    // already dropped it) or, if the session still holds that token, dropped by [resume].
    private suspend fun clearSlot() {
        pending = null
        try {
            store.clear()
        } catch (_: AuthTokenStorageException) {
            // See above.
        }
    }
}

internal val DEFAULT_REVOCATION_RETRY_DELAYS: List<Duration> =
    listOf(Duration.ZERO, 15.seconds, 1.minutes, 5.minutes, 15.minutes)
