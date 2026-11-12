package io.putdotio.android.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingTokenRevocationsTest {
    @Test
    fun `retries stop after the backoff schedule and keep the token for the next trigger`() = runTest {
        val store = InMemoryAuthTokenStore()
        val revoker = ScriptedTokenRevoker(TokenRevocationResult.UNAVAILABLE)
        val revocations = PendingTokenRevocations(listOf(store), revoker, this)

        revocations.revoke(TOKEN)
        testScheduler.advanceUntilIdle()

        assertEquals(DEFAULT_REVOCATION_RETRY_DELAYS.size, revoker.attempts.size)
        assertEquals(TOKEN.reveal(), store.token?.reveal())

        revocations.resume()
        testScheduler.advanceUntilIdle()

        assertEquals(2 * DEFAULT_REVOCATION_RETRY_DELAYS.size, revoker.attempts.size)
    }

    @Test
    fun `a second sign-out queues its token beside an unconfirmed one and a third replaces the oldest`() = runTest {
        val stores = listOf(InMemoryAuthTokenStore(), InMemoryAuthTokenStore())
        val revoker = ScriptedTokenRevoker(TokenRevocationResult.UNAVAILABLE)
        val revocations = PendingTokenRevocations(stores, revoker, this)

        revocations.revoke(TOKEN)
        testScheduler.advanceUntilIdle()
        revocations.revoke(SECOND)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(TOKEN.reveal(), SECOND.reveal()), stores.map { it.token?.reveal() })

        revocations.revoke(THIRD)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(THIRD.reveal(), SECOND.reveal()), stores.map { it.token?.reveal() })

        val restarted = PendingTokenRevocations(stores, ScriptedTokenRevoker(), this)
        restarted.resume()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(null, null), stores.map { it.token })
    }

    @Test
    fun `an unwritable store still revokes for the life of the process`() = runTest {
        val store = InMemoryAuthTokenStore().apply { failWrite = true }
        val revoker = ScriptedTokenRevoker(TokenRevocationResult.UNAVAILABLE, TokenRevocationResult.REVOKED)
        val revocations = PendingTokenRevocations(listOf(store), revoker, this)

        revocations.revoke(TOKEN)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(TOKEN.reveal(), TOKEN.reveal()), revoker.attempts)
        assertNull(store.token)
    }

    @Test
    fun `a signed-out token stays signed out across a restart until a sign-in keeps it`() = runTest {
        val store = InMemoryAuthTokenStore()
        val revoker = ScriptedTokenRevoker(TokenRevocationResult.UNAVAILABLE)
        PendingTokenRevocations(listOf(store), revoker, this).revoke(TOKEN)
        testScheduler.advanceUntilIdle()

        val restarted = PendingTokenRevocations(listOf(store), revoker, this)

        assertTrue(restarted.isSignedOut(TOKEN))
        assertFalse(restarted.isSignedOut(SECOND))
        assertTrue(restarted.keep(TOKEN))
        assertFalse(restarted.isSignedOut(TOKEN))
        assertNull(store.token)
    }

    @Test
    fun `a revoked token stays signed out for the life of the process`() = runTest {
        val store = InMemoryAuthTokenStore()
        val revocations = PendingTokenRevocations(listOf(store), ScriptedTokenRevoker(), this)

        revocations.revoke(TOKEN)
        testScheduler.advanceUntilIdle()

        assertNull(store.token)
        assertTrue(revocations.isSignedOut(TOKEN))
    }

    @Test
    fun `a keep that cannot clear the record fails instead of leaving it beside the session`() = runTest {
        val store = InMemoryAuthTokenStore(TOKEN).apply { failClear = true }
        val revocations = PendingTokenRevocations(listOf(store), ScriptedTokenRevoker(), this)

        assertTrue(runCatching { revocations.keep(TOKEN) }.exceptionOrNull() is AuthTokenStorageException)

        assertEquals(TOKEN.reveal(), store.token?.reveal())
        assertTrue(revocations.isSignedOut(TOKEN))
    }

    @Test
    fun `an unreadable record is not read as no sign-out`() = runTest {
        val store = InMemoryAuthTokenStore(TOKEN).apply { failedReads = 1 }
        val revocations = PendingTokenRevocations(listOf(store), ScriptedTokenRevoker(), this)

        assertTrue(runCatching { revocations.isSignedOut(TOKEN) }.exceptionOrNull() is AuthTokenStorageException)
        assertTrue(revocations.isSignedOut(TOKEN))
    }

    @Test
    fun `an unreadable record is read again on the next attempt`() = runTest {
        val store = InMemoryAuthTokenStore(TOKEN).apply { failedReads = 1 }
        val revoker = ScriptedTokenRevoker()

        PendingTokenRevocations(listOf(store), revoker, this).resume()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(TOKEN.reveal()), revoker.attempts)
        assertNull(store.token)
    }

    private companion object {
        val TOKEN = checkNotNull(AccessToken.parse("signed-out-token"))
        val SECOND = checkNotNull(AccessToken.parse("second-signed-out-token"))
        val THIRD = checkNotNull(AccessToken.parse("third-signed-out-token"))
    }
}
