package io.putdotio.android.auth

import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

public enum class TokenRevocationResult {
    /** put.io deleted the token. */
    REVOKED,

    /** put.io no longer accepts the token, so there is nothing left to revoke. */
    REJECTED,

    /** No verdict (network, server, or unexpected failure); the token may still be valid. */
    UNAVAILABLE,
}

public fun interface AuthTokenRevoker {
    public suspend fun revoke(accessToken: AccessToken): TokenRevocationResult
}

/**
 * Revokes a specific token with its own short-lived client, so a retry never
 * touches the live session's client or revokes whatever token it holds now.
 */
public class PutioAuthTokenRevoker(
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
public interface TokenRevocations {
    /** Remembers [accessToken] until put.io confirms it is gone, and starts revoking it in the background. */
    public suspend fun revoke(accessToken: AccessToken)

    /**
     * Drops a pending revocation of [accessToken] and runs [store] to save it as the new session:
     * put.io handed the same token back. Waits for an attempt already in flight; false, without
     * running [store], when put.io revoked the token anyway. Throws [AuthTokenStorageException]
     * when a record that may hold it cannot be read or cleared, so a stored session never matches
     * a revocation record unless a sign-out left it. A [store] that fails puts the dropped record
     * back, so a token the app does not hold is still revoked.
     */
    public suspend fun keep(accessToken: AccessToken, store: suspend () -> Unit): Boolean

    /**
     * True when a sign-out recorded [accessToken] for revocation: a stored session that still
     * holds it is one whose clear failed, so launch clears it instead of restoring it. Throws
     * [AuthTokenStorageException] when a record cannot be read.
     */
    public suspend fun isSignedOut(accessToken: AccessToken): Boolean

    /** Retries a revocation left unconfirmed by an earlier attempt or process. */
    public fun resume()
}

/**
 * Sign-out is local and immediate; the remote revocation is retried here with
 * bounded backoff until put.io confirms it or rejects the token. Each token is
 * kept only in one of [stores] (Keystore-encrypted) and in memory, so a storage
 * failure still retries for the life of the process. Each trigger ([revoke],
 * [resume]) runs at most [retryDelays].size attempts; an unconfirmed token waits
 * for the next trigger (app start or sign-out). Each store is one slot: a
 * sign-out takes a free one, and only with every slot full does it replace the
 * oldest unconfirmed token. Revocation is idempotent: a token put.io already
 * dropped answers 401.
 *
 * A record that matches the stored session is still revoked: only a sign-out
 * whose clear failed leaves one, since [keep] runs before a sign-in stores its
 * token. A confirmed revocation clears its slot only once [sessionStore] reads
 * without the token, so that sign-out survives a restart;
 * later attempts check the session again without calling put.io. An unreadable
 * record defers the attempt.
 */
public class PendingTokenRevocations(
    stores: List<AuthTokenStore>,
    private val sessionStore: AuthTokenStore,
    private val revoker: AuthTokenRevoker,
    private val scope: CoroutineScope,
    private val retryDelays: List<Duration> = DEFAULT_REVOCATION_RETRY_DELAYS,
) : TokenRevocations {
    private val slots = stores.map(::Slot).also { require(it.isNotEmpty()) }
    private val slotMutex = Mutex()

    // Held for a whole attempt, so keep() learns the outcome of a request already sent.
    private val attemptMutex = Mutex()
    private var writes = 0L
    private val revoked = ArrayDeque<AccessToken>()
    private val jobLock = Any()
    private var retryJob: Job? = null

    override suspend fun revoke(accessToken: AccessToken) {
        slotMutex.withLock {
            val held = slots.associateWith { slot ->
                try {
                    slot.load()
                } catch (_: AuthTokenStorageException) {
                    slot.pending
                }
            }
            val slot = slots.firstOrNull { held[it]?.reveal() == accessToken.reveal() }
                ?: slots.firstOrNull { it.loaded && held[it] == null }
                ?: slots.minBy { it.writtenAt }
            slot.pending = accessToken
            slot.loaded = true
            slot.writtenAt = ++writes
            try {
                slot.store.write(accessToken)
            } catch (_: AuthTokenStorageException) {
                // The in-memory copy still retries until this process ends.
            }
        }
        restartRetries()
    }

    override suspend fun keep(accessToken: AccessToken, store: suspend () -> Unit): Boolean =
        attemptMutex.withLock {
            val dropped = slotMutex.withLock { slots.count { it.drop(accessToken) } } > 0
            if (isRevoked(accessToken)) return@withLock false
            var stored = false
            try {
                store()
                stored = true
            } finally {
                if (!stored && dropped) withContext(NonCancellable) { revoke(accessToken) }
            }
            true
        }

    override suspend fun isSignedOut(accessToken: AccessToken): Boolean =
        isRevoked(accessToken) || slotMutex.withLock { slots.any { it.load()?.reveal() == accessToken.reveal() } }

    private suspend fun isRevoked(accessToken: AccessToken): Boolean =
        slotMutex.withLock { revoked.any { it.reveal() == accessToken.reveal() } }

    override fun resume() {
        synchronized(jobLock) {
            if (retryJob?.isActive != true) {
                retryJob = scope.launch { retry() }
            }
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
            if (attemptMutex.withLock { attempt() }) return
        }
    }

    /** True once nothing is left to revoke. */
    private suspend fun attempt(): Boolean {
        var settled = true
        for (slot in slots) {
            val accessToken = try {
                slotMutex.withLock { slot.load() }
            } catch (_: AuthTokenStorageException) {
                settled = false
                continue
            }
            if (accessToken != null && !settle(slot, accessToken)) settled = false
        }
        return settled
    }

    private suspend fun settle(slot: Slot, accessToken: AccessToken): Boolean {
        if (!isRevoked(accessToken)) {
            if (revoker.revoke(accessToken) == TokenRevocationResult.UNAVAILABLE) return false
            slotMutex.withLock {
                revoked.addLast(accessToken)
                while (revoked.size > slots.size) revoked.removeFirst()
            }
        }
        return slotMutex.withLock {
            when {
                slot.pending !== accessToken -> true
                sessionStore.mayHold(accessToken) -> false
                else -> {
                    slot.clear()
                    true
                }
            }
        }
    }

    private suspend fun AuthTokenStore.mayHold(accessToken: AccessToken): Boolean =
        try {
            read()?.reveal() == accessToken.reveal()
        } catch (_: AuthTokenStorageException) {
            true
        }

    private class Slot(val store: AuthTokenStore) {
        var pending: AccessToken? = null
        var loaded = false
        var writtenAt = 0L

        // Call with slotMutex held. A failed read stays unloaded so the next attempt reads again.
        suspend fun load(): AccessToken? {
            if (!loaded) {
                pending = store.read()
                loaded = true
            }
            return pending
        }

        // Call with slotMutex held.
        suspend fun drop(accessToken: AccessToken): Boolean {
            if (load()?.reveal() != accessToken.reveal()) return false
            store.clear()
            pending = null
            return true
        }

        // Call with slotMutex held. A record left by a failed clear is revoked again (401 if put.io
        // already dropped it) by the next process to load it.
        suspend fun clear() {
            pending = null
            try {
                store.clear()
            } catch (_: AuthTokenStorageException) {
                // See above.
            }
        }
    }
}

internal val DEFAULT_REVOCATION_RETRY_DELAYS: List<Duration> =
    listOf(Duration.ZERO, 15.seconds, 1.minutes, 5.minutes, 15.minutes)
