package io.putdotio.android.auth

import androidx.browser.auth.AuthTabIntent
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class MobileOAuthRuntimeTest {
    @Test
    fun `dispatch reports unexpected callback failures without crashing its scope`() = runBlocking {
        val failure = IllegalStateException("access_token=secret")
        val reportedFailure = CompletableDeferred<Exception>()
        val controller = MobileAuthController(
            oauthConfiguration = MobileOAuthConfiguration.Configured("9677"),
            tokenStore = EmptyAuthTokenStore,
            pendingOAuthAttemptStore = FailingPendingOAuthAttemptStore(failure),
            sessionGateway = UnusedAuthSessionGateway,
        )
        val runtime = MobileOAuthRuntime(
            putioClient = PutioClient(PutioConfig(clientId = "9677", clientName = "test")),
            authController = controller,
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            failureReporter = OAuthRuntimeFailureReporter(reportedFailure::complete),
        )

        runtime.dispatchAuthTabResult(AuthTabIntent.RESULT_OK, "putio://auth#access_token=secret&state=state")

        assertSame(failure, reportedFailure.await())
    }

    @Test
    fun `runtime failure log excludes error messages and callback payloads`() {
        val log = oauthRuntimeFailureLog(IllegalStateException("access_token=secret"))

        assertEquals(
            "event=oauth_auth_tab_result operation=dispatch outcome=failed error_kind=unknown " +
                "error_type=IllegalStateException",
            log,
        )
        assertFalse(log.contains("secret"))
    }

    private object EmptyAuthTokenStore : AuthTokenStore {
        override suspend fun read(): AccessToken? = null

        override suspend fun write(accessToken: AccessToken) = Unit

        override suspend fun clear() = Unit
    }

    private class FailingPendingOAuthAttemptStore(
        private val failure: RuntimeException,
    ) : PendingOAuthAttemptStore {
        override suspend fun read(): PendingOAuthAttempt = throw failure

        override suspend fun write(attempt: PendingOAuthAttempt) = Unit

        override suspend fun clear() = Unit
    }

    private object UnusedAuthSessionGateway : AuthSessionGateway {
        override fun buildLoginUrl(redirectUri: String, state: String): String = error("Not used")

        override fun setAccessToken(accessToken: AccessToken) = Unit

        override fun clearAccessToken() = Unit

        override suspend fun validateSession(): SessionValidationResult = error("Not used")

        override suspend fun logout(): RemoteLogoutResult = error("Not used")
    }
}
