package io.putdotio.android.auth

import io.putdotio.android.auth.AuthControllerTestValues.ACCOUNT
import io.putdotio.android.auth.AuthControllerTestValues.AUTHORIZATION_URL
import io.putdotio.android.auth.AuthControllerTestValues.NOW_EPOCH_MILLIS
import io.putdotio.android.auth.AuthControllerTestValues.OAUTH_STATE
import io.putdotio.android.auth.AuthControllerTestValues.OLD_TOKEN
import io.putdotio.android.auth.AuthControllerTestValues.SIGNED_IN
import io.putdotio.android.auth.AuthControllerTestValues.TOKEN
import io.putdotio.android.auth.AuthControllerTestValues.VALID_CALLBACK
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MobileAuthControllerSessionTest {
    @Test
    fun `restore validates stored token and account`() = runBlocking {
        val fixture = AuthControllerFixture(storedToken = TOKEN)

        fixture.controller.restoreSession()

        assertEquals(listOf("set-token", "validate"), fixture.gateway.calls)
        assertEquals(SIGNED_IN, fixture.controller.state.value)
    }

    @Test
    fun `unexpected restore failure stays retryable`() = runBlocking {
        val fixture = AuthControllerFixture(storedToken = TOKEN)
        fixture.gateway.validationFailure = IllegalStateException("unexpected sdk failure")

        fixture.controller.restoreSession()

        assertEquals(
            MobileAuthState.ValidationUnavailable(SessionValidationSource.RESTORE),
            fixture.controller.state.value,
        )
        assertEquals(TOKEN, fixture.tokenStore.token?.reveal())
        fixture.gateway.validationFailure = null
        assertTrue(fixture.controller.retryValidation())
        assertEquals(SIGNED_IN, fixture.controller.state.value)
    }

    @Test
    fun `secure storage failure resets storage before a new sign in`() = runBlocking {
        val fixture = AuthControllerFixture(storedToken = TOKEN)
        fixture.tokenStore.failRead = true

        fixture.controller.restoreSession()
        val launch = fixture.controller.beginSignIn()

        assertEquals(OAuthLaunchResult.Ready(AUTHORIZATION_URL), launch)
        assertNull(fixture.tokenStore.token)
        assertEquals(OAUTH_STATE, fixture.pendingAttemptStore.attempt?.state)
        assertEquals(MobileAuthState.AwaitingOAuthCallback, fixture.controller.state.value)
    }

    @Test
    fun `secure storage failure survives late OAuth results`() = runBlocking {
        val pendingAttemptStore = FakePendingOAuthAttemptStore(
            attempt = PendingOAuthAttempt(OAUTH_STATE, NOW_EPOCH_MILLIS),
        )
        val fixture = AuthControllerFixture(storedToken = TOKEN, pendingAttemptStore = pendingAttemptStore)
        fixture.tokenStore.failRead = true
        fixture.controller.restoreSession()

        val callback = fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(OAuthCallbackHandlingResult.REJECTED, callback)
        assertFalse(fixture.controller.cancelSignIn())
        assertFalse(fixture.controller.failSignIn())
        assertEquals(OAUTH_STATE, pendingAttemptStore.attempt?.state)
        assertEquals(
            MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable),
            fixture.controller.state.value,
        )
    }

    @Test
    fun `restore completes before a recreated process handles its callback`() = runBlocking {
        val pendingAttemptStore = FakePendingOAuthAttemptStore()
        val firstProcess = AuthControllerFixture(pendingAttemptStore = pendingAttemptStore)
        firstProcess.controller.restoreSession()
        firstProcess.controller.beginSignIn()
        val restoredProcess = AuthControllerFixture(pendingAttemptStore = pendingAttemptStore)
        val readStarted = CompletableDeferred<Unit>()
        val allowRead = CompletableDeferred<Unit>()
        restoredProcess.tokenStore.beforeRead = {
            readStarted.complete(Unit)
            allowRead.await()
        }

        val restore = launch { restoredProcess.controller.restoreSession() }
        readStarted.await()
        val callback = launch { restoredProcess.controller.handleOAuthCallback(VALID_CALLBACK) }
        allowRead.complete(Unit)
        restore.join()
        callback.join()

        assertNull(pendingAttemptStore.attempt)
        assertEquals(SIGNED_IN, restoredProcess.controller.state.value)
    }

    @Test
    fun `callback completes before a recreated process restores its session`() = runBlocking {
        val pendingAttemptStore = FakePendingOAuthAttemptStore()
        val firstProcess = AuthControllerFixture(pendingAttemptStore = pendingAttemptStore)
        firstProcess.controller.restoreSession()
        firstProcess.controller.beginSignIn()
        val restoredProcess = AuthControllerFixture(pendingAttemptStore = pendingAttemptStore)
        val readStarted = CompletableDeferred<Unit>()
        val allowRead = CompletableDeferred<Unit>()
        pendingAttemptStore.beforeRead = {
            readStarted.complete(Unit)
            allowRead.await()
        }

        val callback = launch { restoredProcess.controller.handleOAuthCallback(VALID_CALLBACK) }
        readStarted.await()
        val restore = launch { restoredProcess.controller.restoreSession() }
        allowRead.complete(Unit)
        callback.join()
        restore.join()

        assertNull(pendingAttemptStore.attempt)
        assertEquals(SIGNED_IN, restoredProcess.controller.state.value)
    }

    @Test
    fun `cancelled restore resets initialization so recreation can retry`() = runBlocking {
        val fixture = AuthControllerFixture(storedToken = TOKEN)
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
        val fixture = AuthControllerFixture(
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
        val fixture = AuthControllerFixture(
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
        val fixture = AuthControllerFixture(storedToken = TOKEN)
        fixture.controller.restoreSession()
        fixture.gateway.calls.clear()

        assertTrue(fixture.controller.rejectAuthoritativeSession())

        assertNull(fixture.tokenStore.token)
        assertNull(fixture.gateway.configuredToken)
        assertEquals(listOf("clear-token"), fixture.gateway.calls)
        assertTrue(fixture.revoker.attempts.isEmpty())
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired), fixture.controller.state.value)
        assertFalse(fixture.controller.rejectAuthoritativeSession())
    }

    @Test
    fun `same account reauthentication advances the session identity`() = runBlocking {
        val fixture = AuthControllerFixture(
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
    fun `queued stale rejection preserves new session and current rejection expires it`() = runBlocking {
        val fixture = AuthControllerFixture(
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
        val callbackStarted = CompletableDeferred<Unit>()
        val allowCallback = CompletableDeferred<Unit>()
        fixture.pendingAttemptStore.beforeRead = {
            callbackStarted.complete(Unit)
            allowCallback.await()
        }
        fixture.gateway.calls.clear()
        val callback = async { fixture.controller.handleOAuthCallback(VALID_CALLBACK) }
        callbackStarted.await()
        val staleRejection = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.controller.rejectAuthoritativeSession(firstSession.sessionId)
        }
        assertFalse(staleRejection.isCompleted)
        allowCallback.complete(Unit)

        assertEquals(OAuthCallbackHandlingResult.ACCEPTED, callback.await())
        assertFalse(staleRejection.await())
        val currentSession = fixture.controller.state.value as MobileAuthState.SignedIn
        assertTrue(currentSession.sessionId != firstSession.sessionId)
        assertEquals(ACCOUNT, currentSession.account)
        assertEquals(TOKEN, fixture.tokenStore.token?.reveal())
        assertEquals(TOKEN, fixture.gateway.configuredToken?.reveal())
        assertEquals(listOf("set-token", "validate"), fixture.gateway.calls)

        fixture.gateway.calls.clear()
        assertTrue(fixture.controller.rejectAuthoritativeSession(currentSession.sessionId))
        assertNull(fixture.tokenStore.token)
        assertNull(fixture.gateway.configuredToken)
        assertEquals(listOf("clear-token"), fixture.gateway.calls)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired), fixture.controller.state.value)
    }

    @Test
    fun `authoritative rejection cleanup survives caller cancellation`() = runBlocking {
        val fixture = AuthControllerFixture(storedToken = TOKEN)
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
        val fixture = AuthControllerFixture(
            storedToken = TOKEN,
            validationResults = listOf(
                SessionValidationResult.Unavailable(IOException("offline")),
                SessionValidationResult.Valid(ACCOUNT),
            ),
        )

        fixture.controller.restoreSession()

        assertEquals(TOKEN, fixture.tokenStore.token?.reveal())
        assertEquals(
            MobileAuthState.ValidationUnavailable(SessionValidationSource.RESTORE),
            fixture.controller.state.value,
        )
        assertTrue(fixture.controller.retryValidation())
        assertEquals(SIGNED_IN, fixture.controller.state.value)
    }

    @Test
    fun `cancelled retry restores the prior recoverable failure`() = runBlocking {
        val fixture = AuthControllerFixture(
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

        assertEquals(
            MobileAuthState.ValidationUnavailable(SessionValidationSource.RESTORE),
            fixture.controller.state.value,
        )
        assertNull(fixture.gateway.configuredToken)
        fixture.gateway.validationFailure = null
        assertTrue(fixture.controller.retryValidation())
        assertEquals(SIGNED_IN, fixture.controller.state.value)
    }

    @Test
    fun `logout signs out locally and revokes the token in the background`() = runBlocking {
        val fixture = AuthControllerFixture(storedToken = TOKEN)
        fixture.controller.restoreSession()

        fixture.controller.logout()

        assertNull(fixture.tokenStore.token)
        assertNull(fixture.gateway.configuredToken)
        assertEquals(MobileAuthState.SignedOut(), fixture.controller.state.value)
        assertEquals(listOf(TOKEN), fixture.revoker.attempts)
        assertNull(fixture.revocationStore.token)
    }

    @Test
    fun `sign-out and a rejected session drop what the device keeps for the account`() = runBlocking {
        val fixture = AuthControllerFixture(storedToken = TOKEN)
        fixture.controller.restoreSession()
        assertEquals(0, fixture.accountLocalStateClears)

        fixture.controller.logout()
        assertEquals(1, fixture.accountLocalStateClears)

        val rejected = AuthControllerFixture(storedToken = TOKEN)
        rejected.controller.restoreSession()
        assertTrue(rejected.controller.rejectAuthoritativeSession())
        assertEquals(1, rejected.accountLocalStateClears)
    }

    @Test
    fun `failed revocation is retried with backoff until put io confirms it`() = runBlocking {
        val fixture = AuthControllerFixture(
            storedToken = TOKEN,
            revocations = RevocationScript(
                results = listOf(
                    TokenRevocationResult.UNAVAILABLE,
                    TokenRevocationResult.UNAVAILABLE,
                    TokenRevocationResult.REVOKED,
                ),
            ),
        )
        fixture.controller.restoreSession()

        fixture.controller.logout()

        assertEquals(MobileAuthState.SignedOut(), fixture.controller.state.value)
        assertNull(fixture.tokenStore.token)
        assertEquals(listOf(TOKEN), fixture.revoker.attempts)
        assertEquals(TOKEN, fixture.revocationStore.token?.reveal())
        fixture.revocationScope.advanceTimeBy(15.seconds)
        fixture.revocationScope.testScheduler.runCurrent()
        assertEquals(listOf(TOKEN, TOKEN), fixture.revoker.attempts)
        fixture.revocationScope.advanceUntilIdle()
        assertEquals(listOf(TOKEN, TOKEN, TOKEN), fixture.revoker.attempts)
        assertNull(fixture.revocationStore.token)
    }

    @Test
    fun `revocation rejected by put io is dropped without further attempts`() = runBlocking {
        val fixture = AuthControllerFixture(
            storedToken = TOKEN,
            revocations = RevocationScript(results = listOf(TokenRevocationResult.REJECTED)),
        )
        fixture.controller.restoreSession()

        fixture.controller.logout()
        fixture.revocationScope.advanceUntilIdle()

        assertEquals(listOf(TOKEN), fixture.revoker.attempts)
        assertNull(fixture.revocationStore.token)
        assertEquals(MobileAuthState.SignedOut(), fixture.controller.state.value)
    }

    @Test
    fun `unconfirmed revocation from an earlier process resumes on app start`() = runBlocking {
        val fixture = AuthControllerFixture(revocations = RevocationScript(pending = OLD_TOKEN))

        fixture.controller.restoreSession()

        assertEquals(listOf(OLD_TOKEN), fixture.revoker.attempts)
        assertNull(fixture.revocationStore.token)
    }

    @Test
    fun `logout revokes the session token even when the store cannot be read`() = runBlocking {
        val fixture = AuthControllerFixture(storedToken = TOKEN)
        fixture.controller.restoreSession()
        fixture.tokenStore.failRead = true

        fixture.controller.logout()

        assertEquals(listOf(TOKEN), fixture.revoker.attempts)
        assertNull(fixture.revocationStore.token)
    }

    @Test
    fun `app start keeps a restored session whose token is still recorded for revocation`() = runBlocking {
        val fixture = AuthControllerFixture(storedToken = TOKEN, revocations = RevocationScript(pending = TOKEN))

        fixture.controller.restoreSession()

        assertEquals(SIGNED_IN, fixture.controller.state.value)
        assertTrue(fixture.revoker.attempts.isEmpty())
        assertNull(fixture.revocationStore.token)
    }

    @Test
    fun `a sign-in whose token put io revokes in flight ends signed out`() = runBlocking {
        val fixture = AuthControllerFixture(revocations = RevocationScript(pending = TOKEN))
        val inFlight = CompletableDeferred<Unit>()
        fixture.revoker.gate = inFlight
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()

        val callback = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.controller.handleOAuthCallback(VALID_CALLBACK)
        }
        assertFalse(callback.isCompleted)
        inFlight.complete(Unit)

        assertEquals(OAuthCallbackHandlingResult.ACCEPTED, callback.await())
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired), fixture.controller.state.value)
        assertNull(fixture.tokenStore.token)
        assertEquals(listOf(TOKEN), fixture.revoker.attempts)
    }

    @Test
    fun `signing in with the token awaiting revocation cancels the revocation`() = runBlocking {
        val fixture = AuthControllerFixture(
            revocations = RevocationScript(pending = TOKEN, results = listOf(TokenRevocationResult.UNAVAILABLE)),
        )
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()

        fixture.controller.handleOAuthCallback(VALID_CALLBACK)
        fixture.revocationScope.advanceUntilIdle()

        assertEquals(SIGNED_IN, fixture.controller.state.value)
        assertEquals(listOf(TOKEN), fixture.revoker.attempts)
        assertNull(fixture.revocationStore.token)
    }
}
