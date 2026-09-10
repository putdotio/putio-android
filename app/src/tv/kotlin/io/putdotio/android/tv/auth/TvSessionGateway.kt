package io.putdotio.android.tv.auth

import io.putdotio.android.auth.AccessToken
import io.putdotio.android.auth.isAuthoritativeAuthRejection
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.auth.DeviceCodeAuthState
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioTransportException
import kotlinx.coroutines.flow.Flow

data class TvAccount(
    val userId: Long,
    val username: String,
    val email: String,
    val storage: TvAccountStorage = TvAccountStorage(),
)

data class TvAccountStorage(
    val availableBytes: Long = 0L,
    val sizeBytes: Long = 0L,
    val usedBytes: Long = 0L,
)

internal sealed interface TvSessionValidation {
    data class Valid(
        val account: TvAccount,
    ) : TvSessionValidation

    data object Rejected : TvSessionValidation

    data class Unavailable(
        val cause: Throwable,
    ) : TvSessionValidation
}

internal interface TvSessionGateway {
    fun link(): Flow<DeviceCodeAuthState>

    fun setAccessToken(accessToken: AccessToken)

    fun clearAccessToken()

    suspend fun validateSession(): TvSessionValidation

    /** False when put.io could not revoke the grant; the local session is dropped either way. */
    suspend fun logout(): Boolean
}

internal class PutioTvSessionGateway(
    private val client: PutioClient,
) : TvSessionGateway {
    override fun link(): Flow<DeviceCodeAuthState> = client.deviceCodeAuth.link()

    override fun setAccessToken(accessToken: AccessToken) {
        client.setAccessToken(accessToken.reveal())
    }

    override fun clearAccessToken() {
        client.clearAccessToken()
    }

    override suspend fun validateSession(): TvSessionValidation =
        try {
            if (!client.auth.validateToken().result) {
                TvSessionValidation.Rejected
            } else {
                TvSessionValidation.Valid(client.account.getInfo().toTvAccount())
            }
        } catch (error: PutioException) {
            if (error.isAuthoritativeAuthRejection()) TvSessionValidation.Rejected else TvSessionValidation.Unavailable(error)
        }

    override suspend fun logout(): Boolean =
        try {
            client.auth.logout()
            true
        } catch (_: PutioException) {
            false
        }
}

internal fun AccountInfo.toTvAccount(): TvAccount =
    TvAccount(
        userId = userId,
        username = username,
        email = mail,
        storage = TvAccountStorage(availableBytes = disk.available, sizeBytes = disk.size, usedBytes = disk.used),
    )

enum class TvLinkFailure {
    /** No route to put.io; the same code cannot be reused, so the screen offers a fresh one. */
    NETWORK,

    /** The build carries no usable OAuth client; only a new build fixes this. */
    MISCONFIGURED,

    /** put.io answered with an error the app cannot act on. */
    SERVER,
}

internal fun PutioException.toTvLinkFailure(): TvLinkFailure {
    var current: PutioException = this
    val visited = mutableSetOf<PutioException>()
    while (current is PutioOperationException && visited.add(current)) {
        current = current.underlyingError
    }
    return when (current) {
        is PutioTransportException -> TvLinkFailure.NETWORK
        is PutioConfigurationException -> TvLinkFailure.MISCONFIGURED
        else -> TvLinkFailure.SERVER
    }
}
