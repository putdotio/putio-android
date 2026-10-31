package io.putdotio.android.auth

import io.putdotio.android.auth.AuthControllerTestValues.ACCOUNT
import io.putdotio.android.auth.AuthControllerTestValues.TOKEN
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

// Attempts run in backgroundScope, which only moves as virtual time passes.
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRevalidationTest {
    @Test
    fun `a session that opens offline asks put io again and is confirmed`() = runTest {
        val fixture = offlineFixture(listOf(UNAVAILABLE, UNAVAILABLE, SessionValidationResult.Valid(ACCOUNT)))
        SessionRevalidation(fixture.controller, backgroundScope, DELAYS)

        fixture.controller.restoreSession()
        runCurrent()
        assertFalse(fixture.signedIn().validated)
        advanceTimeBy(6_000L)

        assertTrue(fixture.signedIn().validated)
        assertEquals(3, fixture.validations())
    }

    @Test
    fun `a returned network confirms an offline session without a tap`() = runTest {
        val fixture = offlineFixture(List(1 + DELAYS.size) { UNAVAILABLE } + SessionValidationResult.Valid(ACCOUNT))
        val revalidation = SessionRevalidation(fixture.controller, backgroundScope, DELAYS)
        fixture.controller.restoreSession()
        advanceTimeBy(TEN_MINUTES)
        assertFalse(fixture.signedIn().validated)

        revalidation.onNetworkReturned()
        runCurrent()

        assertTrue(fixture.signedIn().validated)
    }

    @Test
    fun `each start makes a bounded number of attempts`() = runTest {
        val fixture = offlineFixture(List(20) { UNAVAILABLE })
        val revalidation = SessionRevalidation(fixture.controller, backgroundScope, DELAYS)
        fixture.controller.restoreSession()

        advanceTimeBy(TEN_MINUTES)
        assertEquals(1 + DELAYS.size, fixture.validations())
        revalidation.onNetworkReturned()
        advanceTimeBy(TEN_MINUTES)
        advanceTimeBy(TEN_MINUTES)

        assertEquals(1 + 2 * DELAYS.size, fixture.validations())
        assertFalse(fixture.signedIn().validated)
    }

    @Test
    fun `a new return replaces the previous return's attempts`() = runTest {
        val fixture = offlineFixture(List(20) { UNAVAILABLE })
        val revalidation = SessionRevalidation(fixture.controller, backgroundScope, DELAYS)
        fixture.controller.restoreSession()
        advanceTimeBy(TEN_MINUTES)

        revalidation.onNetworkReturned()
        runCurrent()
        revalidation.onNetworkReturned()
        advanceTimeBy(TEN_MINUTES)

        // Restore and the opening attempts, the first return's first attempt, then the second return's.
        assertEquals(1 + DELAYS.size + 1 + DELAYS.size, fixture.validations())
    }

    @Test
    fun `a return retries a session check that has no account to open offline`() = runTest {
        val fixture = AuthControllerFixture(
            storedToken = TOKEN,
            validationResults = listOf(UNAVAILABLE, SessionValidationResult.Valid(ACCOUNT)),
        )
        val revalidation = SessionRevalidation(fixture.controller, backgroundScope, DELAYS)
        fixture.controller.restoreSession()
        advanceTimeBy(TEN_MINUTES)
        assertEquals(1, fixture.validations())

        revalidation.onNetworkReturned()
        runCurrent()

        assertEquals(MobileAuthState.SignedIn(ACCOUNT, MobileAuthSessionId(1L)), fixture.controller.state.value)
    }

    @Test
    fun `a return during the launch check still retries once that check fails`() = runTest {
        val fixture = AuthControllerFixture(
            storedToken = TOKEN,
            validationResults = listOf(UNAVAILABLE, SessionValidationResult.Valid(ACCOUNT)),
        )
        val revalidation = SessionRevalidation(fixture.controller, backgroundScope, DELAYS)
        fixture.gateway.beforeValidate = {
            fixture.gateway.beforeValidate = null
            revalidation.onNetworkReturned()
        }

        fixture.controller.restoreSession()
        runCurrent()

        assertEquals(MobileAuthState.SignedIn(ACCOUNT, MobileAuthSessionId(1L)), fixture.controller.state.value)
    }

    @Test
    fun `a confirmed session asks put io nothing on return`() = runTest {
        val fixture = AuthControllerFixture(storedToken = TOKEN)
        val revalidation = SessionRevalidation(fixture.controller, backgroundScope, DELAYS)
        fixture.controller.restoreSession()

        revalidation.onNetworkReturned()
        advanceTimeBy(TEN_MINUTES)

        assertEquals(1, fixture.validations())
    }

    @Test
    fun `only a network that comes back counts as a return`() {
        var returns = 0
        val online = NetworkReturns { returns += 1 }
        online.onRegistered { true }
        online.onCapabilities(validated = true)
        online.onCapabilities(validated = true)
        assertEquals(0, returns)
        online.onLost()
        online.onCapabilities(validated = true)
        online.onCapabilities(validated = true)
        assertEquals(1, returns)

        val offline = NetworkReturns { returns += 1 }
        offline.onRegistered { false }
        offline.onCapabilities(validated = false)
        offline.onCapabilities(validated = true)
        assertEquals(2, returns)
    }

    private fun offlineFixture(results: List<SessionValidationResult>) =
        AuthControllerFixture(
            storedToken = TOKEN,
            validationResults = results,
        ).apply { validatedAccounts.account = ACCOUNT }

    private fun AuthControllerFixture.signedIn() = controller.state.value as MobileAuthState.SignedIn

    private fun AuthControllerFixture.validations() = gateway.calls.count { it == "validate" }

    private companion object {
        val UNAVAILABLE = SessionValidationResult.Unavailable(IOException("offline"))
        val DELAYS = listOf(0.seconds, 5.seconds, 30.seconds)
        const val TEN_MINUTES = 10 * 60_000L
    }
}
