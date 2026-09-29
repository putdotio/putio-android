package io.putdotio.android.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingTokenRevocationsTest {
    @Test
    fun `retries stop after the backoff schedule and keep the token for the next trigger`() = runTest {
        val store = InMemoryAuthTokenStore()
        val revoker = ScriptedTokenRevoker(TokenRevocationResult.UNAVAILABLE)
        val revocations = PendingTokenRevocations(store, InMemoryAuthTokenStore(), revoker, this)

        revocations.revoke(TOKEN)
        testScheduler.advanceUntilIdle()

        assertEquals(DEFAULT_REVOCATION_RETRY_DELAYS.size, revoker.attempts.size)
        assertEquals(TOKEN.reveal(), store.token?.reveal())

        revocations.resume()
        testScheduler.advanceUntilIdle()

        assertEquals(2 * DEFAULT_REVOCATION_RETRY_DELAYS.size, revoker.attempts.size)
    }

    @Test
    fun `an unwritable store still revokes for the life of the process`() = runTest {
        val store = InMemoryAuthTokenStore().apply { failWrite = true }
        val revoker = ScriptedTokenRevoker(TokenRevocationResult.UNAVAILABLE, TokenRevocationResult.REVOKED)
        val revocations = PendingTokenRevocations(store, InMemoryAuthTokenStore(), revoker, this)

        revocations.revoke(TOKEN)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(TOKEN.reveal(), TOKEN.reveal()), revoker.attempts)
        assertNull(store.token)
    }

    @Test
    fun `a reissued token whose record could not be cleared is not revoked on the next start`() = runTest {
        val store = InMemoryAuthTokenStore(TOKEN).apply { failClear = true }
        val session = InMemoryAuthTokenStore(TOKEN)
        val revoker = ScriptedTokenRevoker()
        PendingTokenRevocations(store, session, revoker, this).keep(TOKEN)
        store.failClear = false

        PendingTokenRevocations(store, session, revoker, this).resume()
        testScheduler.advanceUntilIdle()

        assertTrue(revoker.attempts.isEmpty())
        assertNull(store.token)
        assertEquals(TOKEN.reveal(), session.token?.reveal())
    }

    private companion object {
        val TOKEN = checkNotNull(AccessToken.parse("signed-out-token"))
    }
}
