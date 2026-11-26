package io.putdotio.android.tv.auth

import io.putdotio.android.AccountStorage
import io.putdotio.android.account.InactiveAccountNotice
import io.putdotio.android.account.inactiveAccountNotice
import io.putdotio.android.auth.AccessToken
import io.putdotio.android.auth.isAuthoritativeAuthRejection
import io.putdotio.android.toAccountStorage
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.auth.DeviceCodeAuthState
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioTransportException
import kotlinx.coroutines.flow.Flow

internal data class TvAccount(
    val userId: Long,
    val username: String,
    val email: String,
    val storage: AccountStorage = AccountStorage(),
    val historyEnabled: Boolean = false,
    val avatarUrl: String? = null,
    val inactiveNotice: InactiveAccountNotice? = null,
    val diagnosticsEnabled: Boolean = true,
    val productAnalyticsEnabled: Boolean = false,
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
}

internal class PutioTvSessionGateway(
    private val boundary: TvSdkBoundary,
) : TvSessionGateway {
    constructor(client: PutioClient) : this(PutioClientTvBoundary(client))

    override fun link(): Flow<DeviceCodeAuthState> = boundary.link()

    override fun setAccessToken(accessToken: AccessToken) {
        boundary.setAccessToken(accessToken.reveal())
    }

    override fun clearAccessToken() {
        boundary.clearAccessToken()
    }

    override suspend fun validateSession(): TvSessionValidation =
        try {
            if (!boundary.validateToken()) {
                TvSessionValidation.Rejected
            } else {
                TvSessionValidation.Valid(boundary.getAccountInfo().toTvAccount())
            }
        } catch (error: PutioException) {
            if (error.isAuthoritativeAuthRejection()) {
                TvSessionValidation.Rejected
            } else {
                TvSessionValidation.Unavailable(error)
            }
        }
}

internal interface TvSdkBoundary {
    fun link(): Flow<DeviceCodeAuthState>

    fun setAccessToken(accessToken: String)

    fun clearAccessToken()

    suspend fun validateToken(): Boolean

    suspend fun getAccountInfo(): AccountInfo
}

private class PutioClientTvBoundary(
    private val client: PutioClient,
) : TvSdkBoundary {
    override fun link(): Flow<DeviceCodeAuthState> = client.deviceCodeAuth.link()

    override fun setAccessToken(accessToken: String) = client.setAccessToken(accessToken)

    override fun clearAccessToken() = client.clearAccessToken()

    override suspend fun validateToken(): Boolean = client.auth.validateToken().result

    override suspend fun getAccountInfo(): AccountInfo = client.account.getInfo()
}

internal fun AccountInfo.toTvAccount(): TvAccount =
    TvAccount(
        userId = userId,
        username = username,
        email = mail,
        storage = toAccountStorage(),
        historyEnabled = settings.historyEnabled,
        avatarUrl = avatarUrl.takeIf { it.isNotBlank() },
        inactiveNotice = inactiveAccountNotice(),
        diagnosticsEnabled = settings.diagnosticsEnabled,
        productAnalyticsEnabled = settings.productAnalyticsEnabled,
    )

internal enum class TvLinkFailure {
    NETWORK,

    MISCONFIGURED,

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
