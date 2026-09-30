package io.putdotio.android.auth

import android.content.Context
import androidx.browser.auth.AuthTabIntent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.share.MobileFileShareService
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Robolectric supplies the app files directory the share-out cleanup works on.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
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

    @Test
    fun `a session-exit cleanup that lags the next sign-in keeps the next session's export`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = TestCoroutineScheduler()
        val fixture = SessionExitFixture(
            SessionValidationResult.Valid(ACCOUNT),
            dispatcher = StandardTestDispatcher(scheduler),
            onSessionLeft = { session -> MobileFileShareService.endSession(context, session) },
        )
        fixture.controller.restoreSession()
        scheduler.runCurrent()
        val first = fixture.signedInSession()
        val firstExport = writeExport(context, first)

        // Sign out and back in, then export, all before the cleanup collector runs again.
        fixture.controller.logout()
        fixture.controller.beginSignIn()
        assertEquals(OAuthCallbackHandlingResult.ACCEPTED, fixture.controller.handleOAuthCallback(CALLBACK))
        val next = fixture.signedInSession()
        assertNotEquals(first, next)
        val nextExport = writeExport(context, next)
        scheduler.runCurrent()

        assertFalse(firstExport.exists())
        assertTrue(nextExport.exists())
    }

    private fun writeExport(context: Context, session: MobileAuthSessionId): File =
        File(MobileFileShareService.sessionShares(context, session), "9/poster.jpg").apply {
            parentFile?.mkdirs()
            writeText("bytes")
        }

    private class SessionExitFixture(
        validation: SessionValidationResult,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        onSessionLeft: ((MobileAuthSessionId?) -> Unit)? = null,
    ) {
        var sessionsLeft = 0
        val controller = MobileAuthController(
            oauthConfiguration = MobileOAuthConfiguration.Configured("9677"),
            tokenStore = StoredAuthTokenStore(checkNotNull(AccessToken.parse("token"))),
            pendingOAuthAttemptStore = InMemoryPendingOAuthAttemptStore(),
            sessionGateway = FixedAuthSessionGateway(validation),
            tokenRevocations = NoTokenRevocations,
            stateGenerator = OAuthStateGenerator { OAUTH_STATE },
        )

        init {
            MobileOAuthRuntime(
                putioClient = PutioClient(PutioConfig(clientId = "9677", clientName = "test")),
                authController = controller,
                applicationScope = CoroutineScope(SupervisorJob() + dispatcher),
                onSessionLeft = onSessionLeft ?: { sessionsLeft += 1 },
            )
        }

        fun signedInSession(): MobileAuthSessionId =
            checkNotNull((controller.state.value as? MobileAuthState.SignedIn)?.sessionId) { "Not signed in" }
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
        const val OAUTH_STATE = "fixed-oauth-state"
        const val CALLBACK = "putio://auth?state=$OAUTH_STATE#access_token=token&state=$OAUTH_STATE"
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
