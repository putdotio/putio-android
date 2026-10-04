package io.putdotio.android.tv.auth

import io.putdotio.android.auth.AccessToken
import io.putdotio.android.auth.AuthTokenStorageException
import io.putdotio.android.auth.AuthTokenStore
import kotlinx.coroutines.CancellationException

/** The session's access token: kept in Keystore-backed storage and configured on the gateway. */
internal class TvSessionTokens(
    private val tokenStore: AuthTokenStore,
    private val sessionGateway: TvSessionGateway,
) {
    /** The token the gateway holds; sign-out revokes it without rereading the store. */
    var configured: AccessToken? = null
        private set

    // An unreadable store is not an absent session: the ciphertext may still be
    // there, so the screen says so instead of quietly offering a fresh link.
    suspend fun read(): StoredToken =
        try {
            tokenStore.read()?.let(StoredToken::Present) ?: StoredToken.Absent
        } catch (_: AuthTokenStorageException) {
            StoredToken.Unreadable
        }

    /** False when storage refused the token. */
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

    /** Forgets the token on the gateway and in storage; false when storage could not be cleared. */
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

    data object Unreadable : StoredToken
}
