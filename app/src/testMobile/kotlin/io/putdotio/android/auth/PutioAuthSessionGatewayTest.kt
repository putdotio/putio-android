package io.putdotio.android.auth

import io.putdotio.sdk.account.AccountDisk
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioRequestData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PutioAuthSessionGatewayTest {
    @Test
    fun `valid session requires token validation then account bootstrap`() = runBlocking {
        val boundary = FakePutioSdkAuthBoundary()
        val result = PutioAuthSessionGateway(boundary).validateSession()

        assertEquals(listOf("validate", "account"), boundary.calls)
        assertEquals(
            SessionValidationResult.Valid(
                MobileAccount(
                    userId = 42,
                    username = "user",
                    email = "user@example.com",
                    historyEnabled = true,
                ),
            ),
            result,
        )
    }

    @Test
    fun `false validation rejects without loading account`() = runBlocking {
        val boundary = FakePutioSdkAuthBoundary(validateResult = false)
        val result = PutioAuthSessionGateway(boundary).validateSession()

        assertEquals(listOf("validate"), boundary.calls)
        assertTrue(result is SessionValidationResult.Rejected)
        assertEquals(
            SessionRejectionReason.ValidateReturnedFalse,
            (result as SessionValidationResult.Rejected).reason,
        )
    }

    @Test
    fun `structured unauthorized and forbidden responses reject session`() = runBlocking {
        listOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN).forEach { statusCode ->
            val boundary = FakePutioSdkAuthBoundary(validationFailure = apiOperationFailure(statusCode))
            val result = PutioAuthSessionGateway(boundary).validateSession()

            assertTrue(result is SessionValidationResult.Rejected)
            assertEquals(SessionRejectionReason.Unauthorized, (result as SessionValidationResult.Rejected).reason)
        }
    }

    @Test
    fun `non-auth api response keeps session retryable`() = runBlocking {
        val boundary = FakePutioSdkAuthBoundary(validationFailure = apiOperationFailure(HTTP_SERVER_ERROR))
        val result = PutioAuthSessionGateway(boundary).validateSession()

        assertTrue(result is SessionValidationResult.Unavailable)
    }

    private class FakePutioSdkAuthBoundary(
        private val validateResult: Boolean = true,
        private val validationFailure: PutioOperationException? = null,
    ) : PutioSdkAuthBoundary {
        val calls = mutableListOf<String>()

        override fun buildLoginUrl(redirectUri: String, state: String): String = error("Not used")

        override fun setAccessToken(accessToken: String) = Unit

        override fun clearAccessToken() = Unit

        override suspend fun validateToken(): Boolean {
            calls += "validate"
            validationFailure?.let { throw it }
            return validateResult
        }

        override suspend fun getAccountInfo(): AccountInfo {
            calls += "account"
            return accountInfo()
        }

        override suspend fun logout() = Unit
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_SERVER_ERROR = 503

        fun apiOperationFailure(statusCode: Int): PutioOperationException {
            val apiException = PutioApiException(
                request = PutioRequestData("GET", "https://api.put.io/v2/account/info"),
                resolvedStatusCode = statusCode,
                resolvedErrorType = null,
                envelope = PutioApiErrorEnvelope(statusCode = statusCode),
                responseBody = "{}",
                message = "Request rejected",
            )
            return PutioOperationException(
                domain = "account",
                operation = "getInfo",
                contract = null,
                reason = null,
                underlyingError = apiException,
            )
        }

        fun accountInfo(): AccountInfo = AccountInfo(
            userId = 42,
            username = "user",
            mail = "user@example.com",
            avatarUrl = "https://example.com/avatar.png",
            disk = AccountDisk(available = 1, size = 2, used = 1),
            settings = AccountSettings(sortBy = "NAME_ASC", historyEnabled = true),
            accountStatus = "active",
        )
    }
}
