package io.putdotio.android.tv.auth

import io.putdotio.android.auth.AccessToken
import io.putdotio.android.auth.AuthTokenStorageException
import io.putdotio.android.auth.AuthTokenStore
import kotlinx.coroutines.CancellationException

internal class TvSessionTokens(
    private val tokenStore: AuthTokenStore,
    private val sessionGateway: TvSessionGateway,
) {
    var configured: AccessToken? = null
        private set

    suspend fun read(): StoredToken =
        try {
            tokenStore.read()?.let(StoredToken::Present) ?: StoredToken.Absent
        } catch (_: AuthTokenStorageException) {
            StoredToken.Unreadable
        }

    suspend fun write(accessToken: AccessToken): Boolean =
        try {
            tokenStore.write(accessToken)
            true
        } catch (_: AuthTokenStorageException) {
            false
        }

    fun configure(accessToken: AccessToken) {
        configured = accessToken
        sessionGateway.setAccessToken(accessToken)
    }

    fun clearConfigured() {
        configured = null
        sessionGateway.clearAccessToken()
    }

    suspend fun clear(): Boolean {
        clearConfigured()
        return try {
            tokenStore.clear()
            true
        } catch (_: AuthTokenStorageException) {
            false
        }
    }

    // Gateway implementations are process boundaries; cancellation remains control flow.
    @Suppress("TooGenericExceptionCaught")
    suspend fun validate(): TvSessionValidation =
        try {
            sessionGateway.validateSession()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            TvSessionValidation.Unavailable(error)
        }
}

internal sealed interface StoredToken {
    data class Present(
        val accessToken: AccessToken,
    ) : StoredToken

    data object Absent : StoredToken

    data object SignedOut : StoredToken

    data object Unreadable : StoredToken
}
