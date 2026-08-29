package io.putdotio.android.auth

import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

class MobileAuthControllerTest {
    @Test
    fun `sign in emits sdk url and cancellation returns quietly to signed out`() = runBlocking {
        val fixture = Fixture()
        fixture.controller.restoreSession()

        val launch = fixture.controller.beginSignIn()

        assertEquals(OAuthLaunchResult.Ready(AUTHORIZATION_URL), launch)
        assertEquals(MobileAuthState.AwaitingOAuthCallback, fixture.controller.state.value)
        assertTrue(fixture.controller.cancelSignIn())
        assertEquals(MobileAuthState.SignedOut(), fixture.controller.state.value)
    }

    @Test
    fun `browser launch failure exits the pending attempt with a recoverable error`() = runBlocking {
        val fixture = Fixture()
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()

        assertTrue(fixture.controller.failSignIn())

        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
        assertFalse(fixture.controller.cancelSignIn())
    }

    @Test
    fun `Auth Tab cancellation after process recreation clears the persisted attempt`() = runBlocking {
        val pendingAttemptStore = FakePendingOAuthAttemptStore()
        val firstProcess = Fixture(pendingAttemptStore = pendingAttemptStore)
        firstProcess.controller.restoreSession()
        firstProcess.controller.beginSignIn()

        val restoredProcess = Fixture(pendingAttemptStore = pendingAttemptStore)

        assertTrue(restoredProcess.controller.cancelSignIn())
        assertNull(pendingAttemptStore.attempt)
        assertEquals(MobileAuthState.SignedOut(), restoredProcess.controller.state.value)
    }

    @Test
    fun `Auth Tab failure after restored process clears the persisted attempt`() = runBlocking {
        val pendingAttemptStore = FakePendingOAuthAttemptStore()
        val firstProcess = Fixture(pendingAttemptStore = pendingAttemptStore)
        firstProcess.controller.restoreSession()
        firstProcess.controller.beginSignIn()

        val restoredProcess = Fixture(pendingAttemptStore = pendingAttemptStore)
        restoredProcess.controller.restoreSession()

        assertTrue(restoredProcess.controller.failSignIn())
        assertNull(pendingAttemptStore.attempt)
        assertEquals(
            MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed),
            restoredProcess.controller.state.value,
        )
    }

    @Test
    fun `valid callback persists configures and bootstraps session`() = runBlocking {
        val fixture = Fixture()
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()

        val result = fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(OAuthCallbackHandlingResult.ACCEPTED, result)
        assertEquals(TOKEN, fixture.tokenStore.token?.reveal())
        assertEquals(TOKEN, fixture.gateway.configuredToken?.reveal())
        assertEquals(listOf("clear-token", "build-url", "set-token", "validate"), fixture.gateway.calls)
        assertEquals(SIGNED_IN, fixture.controller.state.value)
    }

    @Test
    fun `mismatched Auth Tab callback stores nothing and consumes pending attempt`() = runBlocking {
        val fixture = Fixture()
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()

        val result = fixture.controller.handleOAuthCallback(
            "putio://auth?state=wrong-state#access_token=$TOKEN&state=wrong-state",
        )

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertNull(fixture.tokenStore.token)
        assertNull(fixture.gateway.configuredToken)
        assertNull(fixture.pendingAttemptStore.attempt)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
        assertFalse(fixture.controller.cancelSignIn())
    }

    @Test
    fun `malformed Auth Tab callback consumes pending attempt`() = runBlocking {
        val fixture = Fixture()
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()

        val result = fixture.controller.handleOAuthCallback("putio://other#state=$OAUTH_STATE")

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertNull(fixture.pendingAttemptStore.attempt)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
    }

    @Test
    fun `provider rejection with matching state consumes pending attempt`() = runBlocking {
        val fixture = Fixture()
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()

        val result = fixture.controller.handleOAuthCallback(
            "putio://auth#error=access_denied&state=$OAUTH_STATE",
        )

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertNull(fixture.pendingAttemptStore.attempt)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
    }

    @Test
    fun `matching malformed callback consumes pending attempt`() = runBlocking {
        val fixture = Fixture()
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()

        val result = fixture.controller.handleOAuthCallback(
            "putio://auth#access_token=one&access_token=two&state=$OAUTH_STATE",
        )

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertNull(fixture.pendingAttemptStore.attempt)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
    }

    @Test
    fun `duplicate matching state consumes pending attempt`() = runBlocking {
        val fixture = Fixture()
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()

        val result = fixture.controller.handleOAuthCallback(
            "putio://auth#access_token=$TOKEN&state=$OAUTH_STATE&state=$OAUTH_STATE",
        )

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertNull(fixture.pendingAttemptStore.attempt)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
    }

    @Test
    fun `pending attempt read and cleanup failure surfaces secure storage error`() = runBlocking {
        val fixture = Fixture()
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()
        fixture.pendingAttemptStore.failRead = true
        fixture.pendingAttemptStore.failClear = true

        val result = fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertEquals(OAUTH_STATE, fixture.pendingAttemptStore.attempt?.state)
        assertEquals(
            MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable),
            fixture.controller.state.value,
        )
    }

    @Test
    fun `callback without in-process attempt is rejected`() = runBlocking {
        val fixture = Fixture()
        fixture.controller.restoreSession()

        val result = fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertNull(fixture.tokenStore.token)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
    }

    @Test
    fun `persisted pending attempt accepts callback after process recreation`() = runBlocking {
        val pendingAttemptStore = FakePendingOAuthAttemptStore()
        val firstProcess = Fixture(pendingAttemptStore = pendingAttemptStore)
        firstProcess.controller.restoreSession()
        firstProcess.controller.beginSignIn()

        val restoredProcess = Fixture(pendingAttemptStore = pendingAttemptStore)
        val result = restoredProcess.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(OAuthCallbackHandlingResult.ACCEPTED, result)
        assertNull(pendingAttemptStore.attempt)
        assertEquals(TOKEN, restoredProcess.tokenStore.token?.reveal())
        assertEquals(SIGNED_IN, restoredProcess.controller.state.value)
    }

    @Test
    fun `expired persisted attempt rejects callback and clears it`() = runBlocking {
        val clock = FakeOAuthAttemptClock(NOW_EPOCH_MILLIS)
        val fixture = Fixture(clock = clock)
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()
        clock.nowEpochMillis += 16 * 60 * 1_000L

        val result = fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertNull(fixture.pendingAttemptStore.attempt)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
    }

    @Test
    fun `expired pending attempt clear failure surfaces secure storage error`() = runBlocking {
        val clock = FakeOAuthAttemptClock(NOW_EPOCH_MILLIS)
        val fixture = Fixture(clock = clock)
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()
        clock.nowEpochMillis += 16 * 60 * 1_000L
        fixture.pendingAttemptStore.failClear = true

        val result = fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertEquals(OAUTH_STATE, fixture.pendingAttemptStore.attempt?.state)
        assertEquals(
            MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable),
            fixture.controller.state.value,
        )
    }

    @Test
    fun `missing pending attempt clear failure surfaces secure storage error`() = runBlocking {
        val fixture = Fixture()
        fixture.controller.restoreSession()
        fixture.pendingAttemptStore.failClear = true

        val result = fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertEquals(
            MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable),
            fixture.controller.state.value,
        )
    }

    @Test
    fun `unsolicited callback cannot suppress restore of a stored session`() = runBlocking {
        val fixture = Fixture(storedToken = TOKEN)

        val result = fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertEquals(MobileAuthState.Initializing, fixture.controller.state.value)

        fixture.controller.restoreSession()

        assertEquals(SIGNED_IN, fixture.controller.state.value)
        assertEquals(TOKEN, fixture.tokenStore.token?.reveal())
    }

    @Test
    fun `restore validates stored token and account`() = runBlocking {
        val fixture = Fixture(storedToken = TOKEN)

        fixture.controller.restoreSession()

        assertEquals(listOf("set-token", "validate"), fixture.gateway.calls)
        assertEquals(SIGNED_IN, fixture.controller.state.value)
    }

    @Test
    fun `cancelled restore resets initialization so recreation can retry`() = runBlocking {
        val fixture = Fixture(storedToken = TOKEN)
        fixture.gateway.validationFailure = CancellationException("activity recreated")

        try {
            fixture.controller.restoreSession()
        } catch (_: CancellationException) {
            // The caller owns cancellation; the controller owns a retryable state.
        }

        assertEquals(MobileAuthState.Initializing, fixture.controller.state.value)
        assertNull(fixture.gateway.configuredToken)
        fixture.gateway.validationFailure = null
        fixture.controller.restoreSession()
        assertEquals(SIGNED_IN, fixture.controller.state.value)
    }

    @Test
    fun `revoked restore cleanup survives caller cancellation`() = runBlocking {
        val fixture = Fixture(
            storedToken = TOKEN,
            validationResults = listOf(
                SessionValidationResult.Rejected(SessionRejectionReason.Unauthorized),
            ),
        )
        val clearStarted = CompletableDeferred<Unit>()
        val allowClear = CompletableDeferred<Unit>()
        fixture.tokenStore.beforeClear = {
            clearStarted.complete(Unit)
            allowClear.await()
        }

        val restore = launch { fixture.controller.restoreSession() }
        clearStarted.await()
        restore.cancel()
        allowClear.complete(Unit)
        restore.join()

        assertNull(fixture.tokenStore.token)
        assertNull(fixture.gateway.configuredToken)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired), fixture.controller.state.value)
    }

    @Test
    fun `authoritative rejection clears sdk and secure storage`() = runBlocking {
        val fixture = Fixture(
            storedToken = TOKEN,
            validationResults = listOf(
                SessionValidationResult.Rejected(SessionRejectionReason.ValidateReturnedFalse),
            ),
        )

        fixture.controller.restoreSession()

        assertNull(fixture.tokenStore.token)
        assertNull(fixture.gateway.configuredToken)
        assertEquals(1, fixture.gateway.clearCount)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired), fixture.controller.state.value)
    }

    @Test
    fun `signed in API rejection expires the local session without remote logout`() = runBlocking {
        val fixture = Fixture(storedToken = TOKEN)
        fixture.controller.restoreSession()
        fixture.gateway.calls.clear()

        assertTrue(fixture.controller.rejectAuthoritativeSession())

        assertNull(fixture.tokenStore.token)
        assertNull(fixture.gateway.configuredToken)
        assertEquals(listOf("clear-token"), fixture.gateway.calls)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired), fixture.controller.state.value)
        assertFalse(fixture.controller.rejectAuthoritativeSession())
    }

    @Test
    fun `same account reauthentication advances the session identity`() = runBlocking {
        val fixture = Fixture(
            storedToken = TOKEN,
            validationResults = listOf(
                SessionValidationResult.Valid(ACCOUNT),
                SessionValidationResult.Valid(ACCOUNT),
            ),
        )
        fixture.controller.restoreSession()
        val firstSession = fixture.controller.state.value as MobileAuthState.SignedIn
        fixture.controller.rejectAuthoritativeSession()

        fixture.controller.beginSignIn()
        assertEquals(
            OAuthCallbackHandlingResult.ACCEPTED,
            fixture.controller.handleOAuthCallback(VALID_CALLBACK),
        )

        val secondSession = fixture.controller.state.value as MobileAuthState.SignedIn
        assertEquals(ACCOUNT, firstSession.account)
        assertEquals(ACCOUNT, secondSession.account)
        assertEquals(1L, firstSession.sessionId.value)
        assertEquals(2L, secondSession.sessionId.value)
    }

    @Test
    fun `authoritative rejection cleanup survives caller cancellation`() = runBlocking {
        val fixture = Fixture(storedToken = TOKEN)
        fixture.controller.restoreSession()
        val clearStarted = CompletableDeferred<Unit>()
        val allowClear = CompletableDeferred<Unit>()
        fixture.tokenStore.beforeClear = {
            clearStarted.complete(Unit)
            allowClear.await()
        }

        val rejection = launch { fixture.controller.rejectAuthoritativeSession() }
        clearStarted.await()
        rejection.cancel()
        allowClear.complete(Unit)
        rejection.join()

        assertNull(fixture.tokenStore.token)
        assertNull(fixture.gateway.configuredToken)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired), fixture.controller.state.value)
    }

    @Test
    fun `temporary validation failure keeps token and retry can complete`() = runBlocking {
        val fixture = Fixture(
            storedToken = TOKEN,
            validationResults = listOf(
                SessionValidationResult.Unavailable(IOException("offline")),
                SessionValidationResult.Valid(ACCOUNT),
            ),
        )

        fixture.controller.restoreSession()

        assertEquals(TOKEN, fixture.tokenStore.token?.reveal())
        assertEquals(MobileAuthState.ValidationUnavailable(SessionValidationSource.RESTORE), fixture.controller.state.value)
        assertTrue(fixture.controller.retryValidation())
        assertEquals(SIGNED_IN, fixture.controller.state.value)
    }

    @Test
    fun `cancelled retry restores the prior recoverable failure`() = runBlocking {
        val fixture = Fixture(
            storedToken = TOKEN,
            validationResults = listOf(
                SessionValidationResult.Unavailable(IOException("offline")),
                SessionValidationResult.Valid(ACCOUNT),
            ),
        )
        fixture.controller.restoreSession()
        fixture.gateway.validationFailure = CancellationException("activity recreated")

        try {
            fixture.controller.retryValidation()
        } catch (_: CancellationException) {
            // The caller owns cancellation; the controller owns a retryable state.
        }

        assertEquals(MobileAuthState.ValidationUnavailable(SessionValidationSource.RESTORE), fixture.controller.state.value)
        assertNull(fixture.gateway.configuredToken)
        fixture.gateway.validationFailure = null
        assertTrue(fixture.controller.retryValidation())
        assertEquals(SIGNED_IN, fixture.controller.state.value)
    }

    @Test
    fun `logout clears local session even when remote logout fails`() = runBlocking {
        val fixture = Fixture(storedToken = TOKEN)
        fixture.gateway.logoutFailure = PutioConfigurationException("offline")
        fixture.controller.restoreSession()

        fixture.controller.logout()

        assertNull(fixture.tokenStore.token)
        assertNull(fixture.gateway.configuredToken)
        assertEquals(MobileAuthState.SignedOut(), fixture.controller.state.value)
    }

    @Test
    fun `missing client id fails closed before state generation`() = runBlocking {
        var generated = false
        val fixture = Fixture(
            configuration = MobileOAuthConfiguration.Unavailable(OAuthConfigurationProblem.MissingClientId),
            stateGenerator = OAuthStateGenerator {
                generated = true
                OAUTH_STATE
            },
        )
        fixture.controller.restoreSession()

        assertEquals(
            MobileAuthState.SignedOut(MobileSignedOutReason.OAuthNotConfigured),
            fixture.controller.state.value,
        )

        val result = fixture.controller.beginSignIn()

        assertEquals(OAuthLaunchResult.NotConfigured, result)
        assertFalse(generated)
        assertFalse(fixture.controller.isOAuthConfigured)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.OAuthNotConfigured), fixture.controller.state.value)
    }

    private class Fixture(
        storedToken: String? = null,
        validationResults: List<SessionValidationResult> = listOf(SessionValidationResult.Valid(ACCOUNT)),
        configuration: MobileOAuthConfiguration = MobileOAuthConfiguration.Configured("9001"),
        stateGenerator: OAuthStateGenerator = OAuthStateGenerator { OAUTH_STATE },
        val pendingAttemptStore: FakePendingOAuthAttemptStore = FakePendingOAuthAttemptStore(),
        clock: OAuthAttemptClock = FakeOAuthAttemptClock(NOW_EPOCH_MILLIS),
    ) {
        val tokenStore = FakeAuthTokenStore(storedToken?.let { checkNotNull(AccessToken.parse(it)) })
        val gateway = FakeAuthSessionGateway(validationResults)
        val controller = MobileAuthController(
            oauthConfiguration = configuration,
            tokenStore = tokenStore,
            pendingOAuthAttemptStore = pendingAttemptStore,
            sessionGateway = gateway,
            stateGenerator = stateGenerator,
            clock = clock,
        )
    }

    private class FakePendingOAuthAttemptStore(
        var attempt: PendingOAuthAttempt? = null,
    ) : PendingOAuthAttemptStore {
        var failRead = false
        var failClear = false

        override suspend fun read(): PendingOAuthAttempt? {
            if (failRead) {
                throw PendingOAuthAttemptStorageException("read")
            }
            return attempt
        }

        override suspend fun write(attempt: PendingOAuthAttempt) {
            this.attempt = attempt
        }

        override suspend fun clear() {
            if (failClear) {
                throw PendingOAuthAttemptStorageException("clear")
            }
            attempt = null
        }
    }

    private class FakeOAuthAttemptClock(
        var nowEpochMillis: Long,
    ) : OAuthAttemptClock {
        override fun nowEpochMillis(): Long = nowEpochMillis
    }

    private class FakeAuthTokenStore(
        var token: AccessToken?,
    ) : AuthTokenStore {
        var beforeClear: (suspend () -> Unit)? = null

        override suspend fun read(): AccessToken? = token

        override suspend fun write(accessToken: AccessToken) {
            token = accessToken
        }

        override suspend fun clear() {
            beforeClear?.invoke()
            token = null
        }
    }

    private class FakeAuthSessionGateway(
        validationResults: List<SessionValidationResult>,
    ) : AuthSessionGateway {
        val calls = mutableListOf<String>()
        val results = ArrayDeque(validationResults)
        var configuredToken: AccessToken? = null
        var clearCount = 0
        var logoutFailure: PutioException? = null
        var validationFailure: Throwable? = null

        override fun buildLoginUrl(redirectUri: String, state: String): String {
            calls += "build-url"
            assertEquals(MOBILE_OAUTH_REDIRECT_URI, redirectUri)
            assertEquals(OAUTH_STATE, state)
            return AUTHORIZATION_URL
        }

        override fun setAccessToken(accessToken: AccessToken) {
            calls += "set-token"
            configuredToken = accessToken
        }

        override fun clearAccessToken() {
            calls += "clear-token"
            clearCount += 1
            configuredToken = null
        }

        override suspend fun validateSession(): SessionValidationResult {
            calls += "validate"
            validationFailure?.let { throw it }
            return results.removeFirst()
        }

        override suspend fun logout(): RemoteLogoutResult {
            calls += "logout"
            return logoutFailure?.let(RemoteLogoutResult::Failed) ?: RemoteLogoutResult.Completed
        }
    }

    private companion object {
        const val TOKEN = "token-value"
        const val OAUTH_STATE = "fixed-oauth-state"
        const val AUTHORIZATION_URL = "https://app.put.io/authenticate?state=fixed-oauth-state"
        const val VALID_CALLBACK = "putio://auth?state=$OAUTH_STATE#access_token=$TOKEN&state=$OAUTH_STATE"
        const val NOW_EPOCH_MILLIS = 1_788_000_000_000L
        val ACCOUNT = MobileAccount(userId = 42, username = "user", email = "user@example.com")
        val SIGNED_IN = MobileAuthState.SignedIn(
            account = ACCOUNT,
            sessionId = MobileAuthSessionId(1L),
        )
    }
}
