package io.putdotio.android.auth

import io.putdotio.android.AccountStorage
import io.putdotio.android.auth.AuthControllerTestValues.ACCOUNT
import io.putdotio.android.auth.AuthControllerTestValues.OLD_TOKEN
import io.putdotio.android.auth.AuthControllerTestValues.TOKEN
import io.putdotio.android.auth.AuthControllerTestValues.VALID_CALLBACK
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MobileAuthControllerOfflineTest {
    @Test
    fun `a restore put io cannot answer opens the shell offline on the account it last confirmed`() = runBlocking {
        val fixture = AuthControllerFixture(
            storedToken = TOKEN,
            validationResults = listOf(UNAVAILABLE),
        ).apply { validatedAccounts.account = ACCOUNT }

        fixture.controller.restoreSession()

        val state = fixture.controller.state.value as MobileAuthState.SignedIn
        assertEquals(ACCOUNT, state.account)
        assertFalse(state.validated)
        assertEquals(TOKEN, fixture.gateway.configuredToken?.reveal())
        assertNull(state.diagnosticsAllowed())
    }

    @Test
    fun `reconnecting confirms the offline session in place`() = runBlocking {
        val refreshed = ACCOUNT.copy(storage = AccountStorage(usedBytes = 7L, sizeBytes = 10L))
        val fixture = AuthControllerFixture(
            storedToken = TOKEN,
            validationResults = listOf(UNAVAILABLE, UNAVAILABLE, SessionValidationResult.Valid(refreshed)),
        ).apply { validatedAccounts.account = ACCOUNT }
        fixture.controller.restoreSession()
        val offline = fixture.controller.state.value as MobileAuthState.SignedIn

        assertFalse(fixture.controller.revalidate())
        assertEquals(offline, fixture.controller.state.value)
        assertTrue(fixture.controller.revalidate())

        assertEquals(MobileAuthState.SignedIn(refreshed, offline.sessionId), fixture.controller.state.value)
        assertEquals(refreshed, fixture.validatedAccounts.account)
        assertTrue(fixture.controller.revalidate())
        assertEquals(listOf("set-token", "validate", "validate", "validate"), fixture.gateway.calls)
    }

    @Test
    fun `a token put io answers for another account starts a new session`() = runBlocking {
        val other = ACCOUNT.copy(userId = 7, username = "other")
        val fixture = AuthControllerFixture(
            storedToken = TOKEN,
            validationResults = listOf(UNAVAILABLE, SessionValidationResult.Valid(other)),
        ).apply { validatedAccounts.account = ACCOUNT }
        fixture.controller.restoreSession()
        val offline = fixture.controller.state.value as MobileAuthState.SignedIn

        assertTrue(fixture.controller.revalidate())

        val online = fixture.controller.state.value as MobileAuthState.SignedIn
        assertEquals(other, online.account)
        assertTrue(online.validated)
        assertTrue(online.sessionId != offline.sessionId)
    }

    @Test
    fun `a session put io rejects at launch still expires with a confirmed account kept`() = runBlocking {
        val fixture = AuthControllerFixture(
            storedToken = TOKEN,
            validationResults = listOf(SessionValidationResult.Rejected(SessionRejectionReason.Unauthorized)),
        ).apply { validatedAccounts.account = ACCOUNT }

        fixture.controller.restoreSession()

        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired), fixture.controller.state.value)
        assertNull(fixture.tokenStore.token)
        assertNull(fixture.validatedAccounts.account)
    }

    @Test
    fun `a session put io rejects on reconnect expires`() = runBlocking {
        val fixture = AuthControllerFixture(
            storedToken = TOKEN,
            validationResults = listOf(UNAVAILABLE, REJECTED),
        ).apply { validatedAccounts.account = ACCOUNT }
        fixture.controller.restoreSession()

        assertTrue(fixture.controller.revalidate())

        assertEquals(MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired), fixture.controller.state.value)
        assertNull(fixture.tokenStore.token)
        assertNull(fixture.validatedAccounts.account)
    }

    @Test
    fun `a restore with no confirmed account still asks to check the session`() = runBlocking {
        val fixture = AuthControllerFixture(storedToken = TOKEN, validationResults = listOf(UNAVAILABLE))

        fixture.controller.restoreSession()

        assertEquals(
            MobileAuthState.ValidationUnavailable(SessionValidationSource.RESTORE),
            fixture.controller.state.value,
        )
        assertTrue(fixture.controller.revalidate())
    }

    @Test
    fun `a validated session is kept for the next offline start`() = runBlocking {
        val fixture = AuthControllerFixture(storedToken = TOKEN)

        fixture.controller.restoreSession()

        assertEquals(ACCOUNT, fixture.validatedAccounts.account)
    }

    @Test
    fun `a new sign-in drops the previous token's account before storing its own`() = runBlocking {
        val previous = ACCOUNT.copy(userId = 7, username = "previous")
        val fixture = AuthControllerFixture(
            storedToken = OLD_TOKEN,
            validationResults = listOf(REJECTED, UNAVAILABLE),
        ).apply { validatedAccounts.account = previous }
        fixture.controller.restoreSession()
        fixture.validatedAccounts.account = previous
        fixture.controller.beginSignIn()

        fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(
            MobileAuthState.ValidationUnavailable(SessionValidationSource.OAUTH_CALLBACK),
            fixture.controller.state.value,
        )
        assertNull(fixture.validatedAccounts.account)
    }

    @Test
    fun `a new token is not stored while the previous account cannot be dropped`() = runBlocking {
        val fixture = AuthControllerFixture().apply { validatedAccounts.account = ACCOUNT }
        fixture.controller.restoreSession()
        fixture.controller.beginSignIn()
        fixture.validatedAccounts.failClear = true

        fixture.controller.handleOAuthCallback(VALID_CALLBACK)

        assertEquals(
            MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable),
            fixture.controller.state.value,
        )
        assertNull(fixture.tokenStore.token)
    }

    @Test
    fun `signing out offline drops the confirmed account`() = runBlocking {
        val fixture = AuthControllerFixture(
            storedToken = TOKEN,
            validationResults = listOf(UNAVAILABLE),
        ).apply { validatedAccounts.account = ACCOUNT }
        fixture.controller.restoreSession()

        fixture.controller.logout()

        assertEquals(MobileAuthState.SignedOut(), fixture.controller.state.value)
        assertNull(fixture.validatedAccounts.account)
        assertNull(fixture.tokenStore.token)
        assertTrue(fixture.controller.revalidate())
    }

    @Test
    fun `a revalidation answer that lands after sign-out is dropped`() = runBlocking {
        val fixture = AuthControllerFixture(
            storedToken = TOKEN,
            validationResults = listOf(UNAVAILABLE),
        ).apply { validatedAccounts.account = ACCOUNT }
        fixture.controller.restoreSession()
        fixture.gateway.beforeValidate = {
            fixture.controller.logout()
            fixture.gateway.results.add(SessionValidationResult.Valid(ACCOUNT))
        }

        assertTrue(fixture.controller.revalidate())

        assertEquals(MobileAuthState.SignedOut(), fixture.controller.state.value)
        assertNull(fixture.validatedAccounts.account)
    }

    private companion object {
        val UNAVAILABLE = SessionValidationResult.Unavailable(IOException("offline"))
        val REJECTED = SessionValidationResult.Rejected(SessionRejectionReason.Unauthorized)
    }
}
