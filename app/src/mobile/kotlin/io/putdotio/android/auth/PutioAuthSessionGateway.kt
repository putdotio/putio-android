package io.putdotio.android.auth

import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException

internal interface AuthSessionGateway {
    fun buildLoginUrl(
        redirectUri: String,
        state: String,
    ): String

    fun setAccessToken(accessToken: AccessToken)

    fun clearAccessToken()

    suspend fun validateSession(): SessionValidationResult

    suspend fun logout(): RemoteLogoutResult
}

internal sealed interface SessionValidationResult {
    data class Valid(
        val account: MobileAccount,
    ) : SessionValidationResult

    data class Rejected(
        val reason: SessionRejectionReason,
        val cause: Throwable? = null,
    ) : SessionValidationResult

    data class Unavailable(
        val cause: Throwable,
    ) : SessionValidationResult
}

internal sealed interface SessionRejectionReason {
    data object ValidateReturnedFalse : SessionRejectionReason

    data object Unauthorized : SessionRejectionReason
}

internal sealed interface RemoteLogoutResult {
    data object Completed : RemoteLogoutResult

    data class Failed(
        val cause: PutioException,
    ) : RemoteLogoutResult
}

internal class PutioAuthSessionGateway(
    private val boundary: PutioSdkAuthBoundary,
) : AuthSessionGateway {
    constructor(client: PutioClient, onTokenChanged: (String?) -> Unit = {}) :
        this(PutioClientAuthBoundary(client, onTokenChanged))

    override fun buildLoginUrl(
        redirectUri: String,
        state: String,
    ): String = boundary.buildLoginUrl(redirectUri, state)

    override fun setAccessToken(accessToken: AccessToken) {
        boundary.setAccessToken(accessToken.reveal())
    }

    override fun clearAccessToken() {
        boundary.clearAccessToken()
    }

    override suspend fun validateSession(): SessionValidationResult =
        try {
            if (!boundary.validateToken()) {
                SessionValidationResult.Rejected(SessionRejectionReason.ValidateReturnedFalse)
            } else {
                SessionValidationResult.Valid(boundary.getAccountInfo().toMobileAccount())
            }
        } catch (error: PutioException) {
            if (error.isAuthoritativeAuthRejection()) {
                SessionValidationResult.Rejected(SessionRejectionReason.Unauthorized, error)
            } else {
                SessionValidationResult.Unavailable(error)
            }
        }

    override suspend fun logout(): RemoteLogoutResult =
        try {
            boundary.logout()
            RemoteLogoutResult.Completed
        } catch (error: PutioException) {
            RemoteLogoutResult.Failed(error)
        }
}

internal interface PutioSdkAuthBoundary {
    fun buildLoginUrl(
        redirectUri: String,
        state: String,
    ): String

    fun setAccessToken(accessToken: String)

    fun clearAccessToken()

    suspend fun validateToken(): Boolean

    suspend fun getAccountInfo(): AccountInfo

    suspend fun logout()
}

private class PutioClientAuthBoundary(
    private val client: PutioClient,
    /** Media downloads and offline playback read the same session token as the SDK. */
    private val onTokenChanged: (String?) -> Unit = {},
) : PutioSdkAuthBoundary {
    override fun buildLoginUrl(
        redirectUri: String,
        state: String,
    ): String = client.auth.buildLoginUrl(redirectUri = redirectUri, state = state)

    override fun setAccessToken(accessToken: String) {
        client.setAccessToken(accessToken)
        onTokenChanged(accessToken)
    }

    override fun clearAccessToken() {
        client.clearAccessToken()
        onTokenChanged(null)
    }

    override suspend fun validateToken(): Boolean = client.auth.validateToken().result

    override suspend fun getAccountInfo(): AccountInfo = client.account.getInfo()

    override suspend fun logout() {
        client.auth.logout()
    }
}

internal fun Throwable.isAuthoritativeAuthRejection(): Boolean {
    val operationReason = (this as? PutioOperationException)?.reason
    if (operationReason is PutioOperationErrorReason.StatusCode && operationReason.statusCode.isAuthRejectionStatus()) {
        return true
    }

    val apiException = findPutioApiException()
    return apiException?.statusCode?.isAuthRejectionStatus() == true
}

private fun Throwable.findPutioApiException(): PutioApiException? {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        if (current is PutioApiException) {
            return current
        }
        current = if (current is PutioOperationException) current.underlyingError else current.cause
    }
    return null
}

private fun Int.isAuthRejectionStatus(): Boolean = this == HTTP_UNAUTHORIZED || this == HTTP_FORBIDDEN

private fun AccountInfo.toMobileAccount(): MobileAccount =
    MobileAccount(
        userId = userId,
        username = username,
        email = mail,
        historyEnabled = settings.historyEnabled,
        avatarUrl = avatarUrl,
        storage =
            MobileAccountStorage(
                availableBytes = disk.available,
                sizeBytes = disk.size,
                usedBytes = disk.used,
            ),
    )

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
