package io.putdotio.android.auth

import kotlinx.coroutines.CompletableDeferred
import java.util.ArrayDeque

/** For fixtures that never sign out. */
public object NoTokenRevocations : TokenRevocations {
    override suspend fun revoke(accessToken: AccessToken): Unit = Unit

    override suspend fun keep(accessToken: AccessToken): Boolean = true

    override fun resume(): Unit = Unit
}

public class InMemoryAuthTokenStore(
    public var token: AccessToken? = null,
) : AuthTokenStore {
    internal var failWrite = false
    internal var failClear = false
    internal var failedReads = 0

    override suspend fun read(): AccessToken? {
        if (failedReads > 0) {
            failedReads--
            throw AuthTokenStorageException("read")
        }
        return token
    }

    override suspend fun write(accessToken: AccessToken) {
        if (failWrite) throw AuthTokenStorageException("write")
        token = accessToken
    }

    override suspend fun clear() {
        if (failClear) throw AuthTokenStorageException("clear")
        token = null
    }
}

/**
 * Answers with [results] in order, then with the last one; records each revoked token value.
 * A set [gate] holds the next answer, as a request still in flight, until it completes.
 */
public class ScriptedTokenRevoker(
    vararg results: TokenRevocationResult,
) : AuthTokenRevoker {
    private val remaining = ArrayDeque(results.toList())
    private var last = TokenRevocationResult.REVOKED
    public val attempts: MutableList<String> = mutableListOf()
    public var gate: CompletableDeferred<Unit>? = null

    override suspend fun revoke(accessToken: AccessToken): TokenRevocationResult {
        attempts += accessToken.reveal()
        gate?.await()
        if (remaining.isNotEmpty()) last = remaining.removeFirst()
        return last
    }
}
