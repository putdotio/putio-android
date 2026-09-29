package io.putdotio.android.auth

import java.util.ArrayDeque

/** For fixtures that never sign out. */
internal object NoTokenRevocations : TokenRevocations {
    override suspend fun revoke(accessToken: AccessToken) = Unit

    override suspend fun keep(accessToken: AccessToken) = Unit

    override fun resume() = Unit
}

internal class InMemoryAuthTokenStore(
    var token: AccessToken? = null,
) : AuthTokenStore {
    var failWrite = false
    var failClear = false

    override suspend fun read(): AccessToken? = token

    override suspend fun write(accessToken: AccessToken) {
        if (failWrite) throw AuthTokenStorageException("write")
        token = accessToken
    }

    override suspend fun clear() {
        if (failClear) throw AuthTokenStorageException("clear")
        token = null
    }
}

/** Answers with [results] in order, then with the last one; records each revoked token value. */
internal class ScriptedTokenRevoker(
    vararg results: TokenRevocationResult,
) : AuthTokenRevoker {
    private val remaining = ArrayDeque(results.toList())
    private var last = TokenRevocationResult.REVOKED
    val attempts = mutableListOf<String>()

    override suspend fun revoke(accessToken: AccessToken): TokenRevocationResult {
        attempts += accessToken.reveal()
        if (remaining.isNotEmpty()) last = remaining.removeFirst()
        return last
    }
}
