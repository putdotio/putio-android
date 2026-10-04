package io.putdotio.android.auth

import io.putdotio.android.auth.AuthControllerTestValues.AUTHORIZATION_URL
import io.putdotio.android.auth.AuthControllerTestValues.OAUTH_STATE
import io.putdotio.android.auth.AuthControllerTestValues.SIGNED_IN
import io.putdotio.android.auth.AuthControllerTestValues.TOKEN
import io.putdotio.android.auth.AuthControllerTestValues.VALID_CALLBACK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

@OptIn(ExperimentalCoroutinesApi::class)
class MobileAuthControllerTest {
    @Test
    fun `sign in emits sdk url and cancellation returns quietly to signed out`() = runBlocking {
        val fixture = AuthControllerFixture()
        fixture.controller.restoreSession()

        val launch = fixture.controller.beginSignIn()

        assertEquals(OAuthLaunchResult.Ready(AUTHORIZATION_URL), launch)
        assertEquals(MobileAuthState.AwaitingOAuthCallback, fixture.controller.state.value)
        assertTrue(fixture.controller.cancelSignIn())
        assertEquals(MobileAuthState.SignedOut(), fixture.controller.state.value)
    }

    @Test
    fun `browser launch failure exits the pending attempt with a recoverable error`() = runBlocking {
        val fixture = AuthControllerFixture()
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()

        assertTrue(fixture.controller.failSignIn())

        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
        assertFalse(fixture.controller.cancelSignIn())
    }

    @Test
    fun `Auth Tab cancellation after process recreation clears the persisted attempt`() = runBlocking {
        val pendingAttemptStore = FakePendingOAuthAttemptStore()
        val firstProcess = AuthControllerFixture(pendingAttemptStore = pendingAttemptStore)
        firstProcess.controller.restoreSession()
        firstProcess.controller.beginSignIn()

        val restoredProcess = AuthControllerFixture(pendingAttemptStore = pendingAttemptStore)

        assertTrue(restoredProcess.controller.cancelSignIn())
        assertNull(pendingAttemptStore.attempt)
        assertEquals(MobileAuthState.SignedOut(), restoredProcess.controller.state.value)
    }

    @Test
    fun `Auth Tab failure after restored process clears the persisted attempt`() = runBlocking {
        val pendingAttemptStore = FakePendingOAuthAttemptStore()
        val firstProcess = AuthControllerFixture(pendingAttemptStore = pendingAttemptStore)
        firstProcess.controller.restoreSession()
        firstProcess.controller.beginSignIn()

        val restoredProcess = AuthControllerFixture(pendingAttemptStore = pendingAttemptStore)
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
        val fixture = AuthControllerFixture()
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
    fun `stale Auth Tab callback preserves a newer pending attempt`() = runBlocking {
        val states = ArrayDeque(listOf("older-oauth-state", "newer-oauth-state"))
        val fixture = AuthControllerFixture(stateGenerator = OAuthStateGenerator { states.removeFirst() })
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()
        fixture.controller.cancelSignIn()
        fixture.controller.beginSignIn()

        val result = fixture.controller.handleOAuthCallback(
            "putio://auth?state=older-oauth-state#access_token=$TOKEN&state=older-oauth-state",
        )

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertNull(fixture.tokenStore.token)
        assertNull(fixture.gateway.configuredToken)
        assertEquals("newer-oauth-state", fixture.pendingAttemptStore.attempt?.state)
        assertEquals(MobileAuthState.AwaitingOAuthCallback, fixture.controller.state.value)

        assertEquals(
            OAuthCallbackHandlingResult.ACCEPTED,
            fixture.controller.handleOAuthCallback(
                "putio://auth?state=newer-oauth-state#access_token=$TOKEN&state=newer-oauth-state",
            ),
        )
        assertEquals(SIGNED_IN, fixture.controller.state.value)
    }

    @Test
    fun `stale callback restores awaiting state across process recreation`() = runBlocking {
        listOf(false, true).forEach { restoreBeforeCallback ->
            val pendingStore = FakePendingOAuthAttemptStore()
            val firstProcess = AuthControllerFixture(pendingAttemptStore = pendingStore)
            firstProcess.controller.restoreSession()
            firstProcess.controller.beginSignIn()
            val currentAttempt = pendingStore.attempt
            val restoredProcess = AuthControllerFixture(pendingAttemptStore = pendingStore)
            if (restoreBeforeCallback) restoredProcess.controller.restoreSession()

            val result = restoredProcess.controller.handleOAuthCallback(
                "putio://auth#state=older-oauth-state&access_token=$TOKEN",
            )
            restoredProcess.controller.restoreSession()

            assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
            assertEquals(MobileAuthState.AwaitingOAuthCallback, restoredProcess.controller.state.value)
            assertEquals(OAuthLaunchResult.NotAllowed, restoredProcess.controller.beginSignIn())
            assertEquals(currentAttempt, pendingStore.attempt)
            assertNull(restoredProcess.tokenStore.token)
            assertEquals(
                OAuthCallbackHandlingResult.ACCEPTED,
                restoredProcess.controller.handleOAuthCallback(VALID_CALLBACK),
            )
            assertNull(pendingStore.attempt)
            assertEquals(SIGNED_IN, restoredProcess.controller.state.value)
        }
    }

    @Test
    fun `contradictory query and fragment states consume the matching pending attempt`() = runBlocking {
        listOf(
            "putio://auth?state=older-oauth-state#state=$OAUTH_STATE&access_token=$TOKEN",
            "putio://auth?state=$OAUTH_STATE#state=older-oauth-state&access_token=$TOKEN",
        ).forEach { callback ->
            val fixture = AuthControllerFixture()
            fixture.controller.restoreSession()
            fixture.controller.beginSignIn()

            val result = fixture.controller.handleOAuthCallback(callback)

            assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
            assertNull(fixture.pendingAttemptStore.attempt)
            assertNull(fixture.tokenStore.token)
            assertNull(fixture.gateway.configuredToken)
            assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
        }
    }

    @Test
    fun `malformed Auth Tab callback consumes pending attempt`() = runBlocking {
        val fixture = AuthControllerFixture()
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()

        val result = fixture.controller.handleOAuthCallback("putio://other#state=$OAUTH_STATE")

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertNull(fixture.pendingAttemptStore.attempt)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
    }

    @Test
    fun `provider rejection with matching state consumes pending attempt`() = runBlocking {
        val fixture = AuthControllerFixture()
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
        val fixture = AuthControllerFixture()
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
        val fixture = AuthControllerFixture()
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
        val fixture = AuthControllerFixture()
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
        val fixture = AuthControllerFixture()
        fixture.controller.restoreSession()

        val result = fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertNull(fixture.tokenStore.token)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
    }

    @Test
    fun `persisted pending attempt accepts callback after process recreation`() = runBlocking {
        val pendingAttemptStore = FakePendingOAuthAttemptStore()
        val firstProcess = AuthControllerFixture(pendingAttemptStore = pendingAttemptStore)
        firstProcess.controller.restoreSession()
        firstProcess.controller.beginSignIn()

        val restoredProcess = AuthControllerFixture(pendingAttemptStore = pendingAttemptStore)
        val result = restoredProcess.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(OAuthCallbackHandlingResult.ACCEPTED, result)
        assertNull(pendingAttemptStore.attempt)
        assertEquals(TOKEN, restoredProcess.tokenStore.token?.reveal())
        assertEquals(SIGNED_IN, restoredProcess.controller.state.value)
    }

    @Test
    fun `expired persisted attempt rejects callback and clears it`() = runBlocking {
        val fixture = AuthControllerFixture()
        val clock = fixture.clock
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
        val fixture = AuthControllerFixture()
        val clock = fixture.clock
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
        val fixture = AuthControllerFixture()
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
        val fixture = AuthControllerFixture(storedToken = TOKEN)

        val result = fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(OAuthCallbackHandlingResult.REJECTED, result)
        assertEquals(MobileAuthState.Initializing, fixture.controller.state.value)

        fixture.controller.restoreSession()

        assertEquals(SIGNED_IN, fixture.controller.state.value)
        assertEquals(TOKEN, fixture.tokenStore.token?.reveal())
    }

    @Test
    fun `logout while awaiting OAuth clears the attempt and rejects a late callback`() = runBlocking {
        val fixture = AuthControllerFixture()
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()

        fixture.controller.logout()
        val callback = fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertNull(fixture.pendingAttemptStore.attempt)
        assertNull(fixture.tokenStore.token)
        assertEquals(OAuthCallbackHandlingResult.REJECTED, callback)
        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed), fixture.controller.state.value)
    }

    @Test
    fun `missing client id fails closed before state generation`() = runBlocking {
        var generated = false
        val fixture = AuthControllerFixture(
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
        assertEquals(
            MobileAuthState.SignedOut(MobileSignedOutReason.OAuthNotConfigured),
            fixture.controller.state.value,
        )
    }
}
