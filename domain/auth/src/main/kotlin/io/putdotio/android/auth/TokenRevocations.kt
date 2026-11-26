package io.putdotio.android.auth

import io.putdotio.sdk.PutioClient
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
    REVOKED,

    REJECTED,

    UNAVAILABLE,
}

public fun interface AuthTokenRevoker {
    public suspend fun revoke(accessToken: AccessToken): TokenRevocationResult
}

public class PutioAuthTokenRevoker(
    private val client: PutioClient,
) : AuthTokenRevoker {
    // The SDK is a process boundary; any failure other than cancellation is "no verdict yet".
    @Suppress("TooGenericExceptionCaught")
    override suspend fun revoke(accessToken: AccessToken): TokenRevocationResult =
        try {
            client.auth.logout(accessToken.reveal())
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

public interface TokenRevocations {
    public suspend fun revoke(accessToken: AccessToken)

    public suspend fun keep(accessToken: AccessToken, store: suspend () -> Unit): Boolean

    public suspend fun isSignedOut(accessToken: AccessToken): Boolean

    public fun resume()
}

public class PendingTokenRevocations(
    stores: List<AuthTokenStore>,
    private val sessionStore: AuthTokenStore,
    private val revoker: AuthTokenRevoker,
    private val scope: CoroutineScope,
    private val retryDelays: List<Duration> = DEFAULT_REVOCATION_RETRY_DELAYS,
) : TokenRevocations {
    private val slots = stores.map(::Slot).also { require(it.isNotEmpty()) }
    private val slotMutex = Mutex()

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
            var dropped = false
            var settled = false
            try {
                slotMutex.withLock {
                    val matching = slots.filter { it.load()?.reveal() == accessToken.reveal() }
                    matching.forEach { slot ->
                        slot.drop()
                        dropped = true
                    }
                }
                if (isRevoked(accessToken)) {
                    settled = true
                    return@withLock false
                }
                store()
                settled = true
            } finally {
                if (!settled && dropped) withContext(NonCancellable) { revoke(accessToken) }
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

        suspend fun load(): AccessToken? {
            if (!loaded) {
                pending = store.read()
                loaded = true
            }
            return pending
        }

        suspend fun drop() {
            store.clear()
            pending = null
        }

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
