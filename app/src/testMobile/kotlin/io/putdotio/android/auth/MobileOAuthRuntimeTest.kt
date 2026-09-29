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
import org.junit.Assert.assertTrue
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
            tokenRevocations = NoTokenRevocations,
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
    fun `a session rejected without any UI still runs the session-exit cleanup`() = runBlocking {
        val fixture = SessionExitFixture(SessionValidationResult.Valid(ACCOUNT))

        fixture.controller.restoreSession()
        assertEquals(0, fixture.sessionsLeft)
        assertTrue(fixture.controller.rejectAuthoritativeSession())

        assertEquals(1, fixture.sessionsLeft)
    }

    @Test
    fun `a rejected cold-start restore ends the persisted session once`() = runBlocking {
        val fixture = SessionExitFixture(SessionValidationResult.Rejected(SessionRejectionReason.Unauthorized))

        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()
        fixture.controller.cancelSignIn()

        assertEquals(1, fixture.sessionsLeft)
    }

    private class SessionExitFixture(validation: SessionValidationResult) {
        var sessionsLeft = 0
        val controller = MobileAuthController(
            oauthConfiguration = MobileOAuthConfiguration.Configured("9677"),
            tokenStore = StoredAuthTokenStore(checkNotNull(AccessToken.parse("token"))),
            pendingOAuthAttemptStore = InMemoryPendingOAuthAttemptStore(),
            sessionGateway = FixedAuthSessionGateway(validation),
            tokenRevocations = NoTokenRevocations,
        )

        init {
            MobileOAuthRuntime(
                putioClient = PutioClient(PutioConfig(clientId = "9677", clientName = "test")),
                authController = controller,
                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                onSessionLeft = { sessionsLeft += 1 },
            )
        }
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

    private class StoredAuthTokenStore(private var token: AccessToken?) : AuthTokenStore {
        override suspend fun read(): AccessToken? = token

        override suspend fun write(accessToken: AccessToken) {
            token = accessToken
        }

        override suspend fun clear() {
            token = null
        }
    }

    private class InMemoryPendingOAuthAttemptStore : PendingOAuthAttemptStore {
        private var attempt: PendingOAuthAttempt? = null

        override suspend fun read(): PendingOAuthAttempt? = attempt

        override suspend fun write(attempt: PendingOAuthAttempt) {
            this.attempt = attempt
        }

        override suspend fun clear() {
            attempt = null
        }
    }

    private class FixedAuthSessionGateway(private val validation: SessionValidationResult) : AuthSessionGateway {
        override fun buildLoginUrl(redirectUri: String, state: String): String = "https://app.put.io/authenticate"

        override fun setAccessToken(accessToken: AccessToken) = Unit

        override fun clearAccessToken() = Unit

        override suspend fun validateSession(): SessionValidationResult = validation
    }

    private companion object {
        val ACCOUNT = MobileAccount(userId = 1L, username = "user", email = "user@example.com")
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
    }
}
