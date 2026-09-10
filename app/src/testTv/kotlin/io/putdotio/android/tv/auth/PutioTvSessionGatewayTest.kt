package io.putdotio.android.tv.auth

import io.putdotio.sdk.account.AccountDisk
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.auth.DeviceCodeAuthState
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.errors.PutioTransportException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PutioTvSessionGatewayTest {
    @Test
    fun `a true verdict loads the account`() = runBlocking {
        val boundary = FakeBoundary()

        val result = PutioTvSessionGateway(boundary).validateSession()

        assertEquals(listOf("validate", "account"), boundary.calls)
        assertEquals(
            TvSessionValidation.Valid(
                TvAccount(
                    userId = 42,
                    username = "user",
                    email = "user@example.com",
                    storage = TvAccountStorage(availableBytes = 1, sizeBytes = 2, usedBytes = 1),
                ),
            ),
            result,
        )
    }

    @Test
    fun `a false verdict rejects without loading the account`() = runBlocking {
        val boundary = FakeBoundary(validateResult = false)

        assertEquals(TvSessionValidation.Rejected, PutioTvSessionGateway(boundary).validateSession())
        assertEquals(listOf("validate"), boundary.calls)
    }

    @Test
    fun `401 and 403 anywhere in the chain reject`() = runBlocking {
        listOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN).forEach { statusCode ->
            val boundary = FakeBoundary(validationFailure = apiOperationFailure(statusCode))

            assertEquals(TvSessionValidation.Rejected, PutioTvSessionGateway(boundary).validateSession())
        }
    }

    @Test
    fun `transport and server failures are unavailable and keep the token`() = runBlocking {
        listOf(transportFailure(), apiOperationFailure(HTTP_SERVER_ERROR)).forEach { failure ->
            val boundary = FakeBoundary(validationFailure = failure)

            val result = PutioTvSessionGateway(boundary).validateSession()

            assertTrue(result is TvSessionValidation.Unavailable)
            assertEquals(failure, (result as TvSessionValidation.Unavailable).cause)
        }
    }

    @Test
    fun `logout reports whether the grant was revoked`() = runBlocking {
        assertTrue(PutioTvSessionGateway(FakeBoundary()).logout())
        assertFalse(PutioTvSessionGateway(FakeBoundary(logoutFailure = transportFailure())).logout())
    }

    private class FakeBoundary(
        private val validateResult: Boolean = true,
        private val validationFailure: PutioException? = null,
        private val logoutFailure: PutioException? = null,
    ) : TvSdkBoundary {
        val calls = mutableListOf<String>()

        override fun link(): Flow<DeviceCodeAuthState> = emptyFlow()

        override fun setAccessToken(accessToken: String) = Unit

        override fun clearAccessToken() = Unit

        override suspend fun validateToken(): Boolean {
            calls += "validate"
            validationFailure?.let { throw it }
            return validateResult
        }

        override suspend fun getAccountInfo(): AccountInfo {
            calls += "account"
            return AccountInfo(
                userId = 42,
                username = "user",
                mail = "user@example.com",
                avatarUrl = "https://example.com/avatar.png",
                disk = AccountDisk(available = 1, size = 2, used = 1),
                settings = AccountSettings(sortBy = "NAME_ASC"),
                accountStatus = "active",
            )
        }

        override suspend fun logout() {
            logoutFailure?.let { throw it }
        }
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_SERVER_ERROR = 503

        fun apiOperationFailure(statusCode: Int): PutioOperationException =
            PutioOperationException(
                domain = "auth",
                operation = "validateToken",
                contract = null,
                reason = null,
                underlyingError = PutioApiException(
                    request = PutioRequestData("GET", "https://api.put.io/v2/oauth2/validate"),
                    resolvedStatusCode = statusCode,
                    resolvedErrorType = null,
                    envelope = PutioApiErrorEnvelope(statusCode = statusCode),
                    responseBody = "{}",
                    message = "Request rejected",
                ),
            )

        fun transportFailure(): PutioOperationException =
            PutioOperationException(
                domain = "auth",
                operation = "validateToken",
                contract = null,
                reason = null,
                underlyingError = PutioTransportException(
                    PutioRequestData("GET", "https://api.put.io/v2/oauth2/validate"),
                    IOException("offline"),
                ),
            )
    }
}
