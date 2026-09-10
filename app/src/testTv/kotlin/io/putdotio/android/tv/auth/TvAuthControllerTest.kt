package io.putdotio.android.tv.auth

import io.putdotio.android.auth.AccessToken
import io.putdotio.android.auth.AuthTokenStorageException
import io.putdotio.android.auth.AuthTokenStore
import io.putdotio.sdk.account.AccountDisk
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.auth.DeviceCodeAuthState
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.errors.PutioTransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TvAuthControllerTest {
    @Test
    fun `a stored token restores straight into the shell`() = runTest {
        val harness = Harness(storedToken = "stored-token")

        harness.controller.restoreSession()

        val signedIn = harness.controller.state.value as TvAuthState.SignedIn
        assertEquals("user", signedIn.account.username)
        assertEquals(listOf("set", "validate"), harness.gateway.calls)
    }

    @Test
    fun `no stored token starts a link attempt and shows the code`() = runTest {
        val harness = Harness()

        harness.controller.restoreSession()
        harness.gateway.emit(DeviceCodeAuthState.Requesting)
        assertEquals(TvAuthState.Linking(TvLinkPhase.RequestingCode), harness.controller.state.value)
        harness.gateway.emit(DeviceCodeAuthState.AwaitingLink("ABCDEF", "https://put.io/link", budget = BUDGET))

        assertEquals(TvAuthState.Linking(TvLinkPhase.AwaitingLink("ABCDEF")), harness.controller.state.value)
    }

    @Test
    fun `a linked code persists the token before the shell appears`() = runTest {
        val harness = Harness()
        harness.controller.restoreSession()

        harness.gateway.emit(DeviceCodeAuthState.Validating)
        assertEquals(TvAuthState.Linking(TvLinkPhase.Validating), harness.controller.state.value)
        harness.gateway.emit(DeviceCodeAuthState.Linked("fresh-token", accountInfo()))

        assertEquals("fresh-token", harness.tokenStore.stored?.reveal())
        assertTrue(harness.controller.state.value is TvAuthState.SignedIn)
        assertEquals(listOf("link", "set"), harness.gateway.calls)
    }

    @Test
    fun `an expired code stops polling until the user asks for a new one`() = runTest {
        val harness = Harness()
        harness.controller.restoreSession()

        harness.gateway.emit(DeviceCodeAuthState.Expired(DeviceCodeAuthState.Expired.Reason.BUDGET_ELAPSED))
        assertEquals(
            TvAuthState.Linking(TvLinkPhase.Stopped(TvLinkStop.CodeExpired)),
            harness.controller.state.value,
        )

        assertTrue(harness.controller.requestNewCode())
        assertEquals(TvAuthState.Linking(TvLinkPhase.RequestingCode), harness.controller.state.value)
        assertEquals(2, harness.gateway.linkAttempts)
    }

    @Test
    fun `a new code request replaces a live attempt but not one mid validation`() = runTest {
        val harness = Harness()
        harness.controller.restoreSession()
        harness.gateway.emit(DeviceCodeAuthState.AwaitingLink("ABCDEF", "https://put.io/link", budget = BUDGET))

        assertTrue(harness.controller.requestNewCode())
        assertEquals(2, harness.gateway.linkAttempts)
        harness.gateway.emit(DeviceCodeAuthState.Validating)

        assertFalse(harness.controller.requestNewCode())
        assertEquals(2, harness.gateway.linkAttempts)
    }

    @Test
    fun `a new code request joins the abandoned attempt before starting another`() = runTest {
        val harness = Harness()
        harness.controller.restoreSession()
        harness.gateway.emit(DeviceCodeAuthState.AwaitingLink("ABCDEF", "https://put.io/link", budget = BUDGET))

        assertTrue(harness.controller.requestNewCode())

        assertEquals(listOf("link", "link-cancelled", "link"), harness.gateway.calls)
    }

    @Test
    fun `an unreadable token store stops at storage unavailable instead of offering a fresh link`() = runTest {
        val harness = Harness(tokenStore = FakeTokenStore(readFails = true))

        harness.controller.restoreSession()

        assertEquals(
            TvAuthState.Linking(TvLinkPhase.Stopped(TvLinkStop.StorageUnavailable)),
            harness.controller.state.value,
        )
        assertEquals(0, harness.gateway.linkAttempts)
    }

    @Test
    fun `link failures are classified for the screen`() = runTest {
        val harness = Harness()
        harness.controller.restoreSession()

        harness.gateway.emit(DeviceCodeAuthState.Failed(transportFailure()))
        assertEquals(stopped(TvLinkFailure.NETWORK), harness.controller.state.value)

        harness.controller.requestNewCode()
        harness.gateway.emit(DeviceCodeAuthState.Failed(PutioConfigurationException("no client")))
        assertEquals(stopped(TvLinkFailure.MISCONFIGURED), harness.controller.state.value)
    }

    @Test
    fun `a token store that cannot write discards the linked token`() = runTest {
        val harness = Harness(tokenStore = FakeTokenStore(writeFails = true))
        harness.controller.restoreSession()

        harness.gateway.emit(DeviceCodeAuthState.Linked("fresh-token", accountInfo()))

        assertEquals(
            TvAuthState.Linking(TvLinkPhase.Stopped(TvLinkStop.StorageUnavailable)),
            harness.controller.state.value,
        )
        assertFalse(harness.gateway.calls.contains("set"))
    }

    @Test
    fun `a rejected stored session clears the token and asks for a new code as expired`() = runTest {
        val harness = Harness(storedToken = "stale", validation = TvSessionValidation.Rejected)

        harness.controller.restoreSession()

        assertNull(harness.tokenStore.stored)
        assertEquals(TvAuthState.Linking(TvLinkPhase.RequestingCode, sessionExpired = true), harness.controller.state.value)
        harness.gateway.emit(DeviceCodeAuthState.AwaitingLink("ABCDEF", "https://put.io/link", budget = BUDGET))
        assertEquals(
            TvAuthState.Linking(TvLinkPhase.AwaitingLink("ABCDEF"), sessionExpired = true),
            harness.controller.state.value,
        )
    }

    @Test
    fun `an unreachable validation keeps the token and offers retry`() = runTest {
        val harness = Harness(
            storedToken = "stored-token",
            validation = TvSessionValidation.Unavailable(IOException("offline")),
        )

        harness.controller.restoreSession()
        assertEquals(TvAuthState.ValidationUnavailable(TvSessionValidationSource.RESTORE), harness.controller.state.value)
        assertEquals("stored-token", harness.tokenStore.stored?.reveal())

        harness.gateway.validation = TvSessionValidation.Valid(accountInfo().toTvAccount())
        assertTrue(harness.controller.retryValidation())
        assertTrue(harness.controller.state.value is TvAuthState.SignedIn)
    }

    @Test
    fun `logout revokes then drops the local session and returns to linking`() = runTest {
        val harness = Harness(storedToken = "stored-token")
        harness.controller.restoreSession()

        harness.controller.logout()

        assertNull(harness.tokenStore.stored)
        assertEquals(TvAuthState.Linking(TvLinkPhase.RequestingCode), harness.controller.state.value)
        assertEquals(listOf("set", "validate", "logout", "clear", "link"), harness.gateway.calls)
    }

    @Test
    fun `an authoritative rejection for a stale session id is ignored`() = runTest {
        val harness = Harness(storedToken = "stored-token")
        harness.controller.restoreSession()
        val current = (harness.controller.state.value as TvAuthState.SignedIn).sessionId

        assertFalse(harness.controller.rejectAuthoritativeSession(TvAuthSessionId(current.value + 1)))
        assertTrue(harness.controller.state.value is TvAuthState.SignedIn)
        assertTrue(harness.controller.rejectAuthoritativeSession(current))
        assertEquals(TvAuthState.Linking(TvLinkPhase.RequestingCode, sessionExpired = true), harness.controller.state.value)
    }

    private inner class Harness(
        storedToken: String? = null,
        validation: TvSessionValidation = TvSessionValidation.Valid(accountInfo().toTvAccount()),
        val tokenStore: FakeTokenStore = FakeTokenStore(),
    ) {
        val gateway = FakeGateway(validation)
        val scope = TestScope(UnconfinedTestDispatcher())
        val controller: TvAuthController

        init {
            tokenStore.stored = storedToken?.let { checkNotNull(AccessToken.parse(it)) }
            controller = TvAuthController(tokenStore, gateway, scope)
        }
    }

    private class FakeTokenStore(
        private val writeFails: Boolean = false,
        private val readFails: Boolean = false,
    ) : AuthTokenStore {
        var stored: AccessToken? = null

        override suspend fun read(): AccessToken? {
            if (readFails) throw AuthTokenStorageException("read")
            return stored
        }

        override suspend fun write(accessToken: AccessToken) {
            if (writeFails) throw AuthTokenStorageException("write")
            stored = accessToken
        }

        override suspend fun clear() {
            stored = null
        }
    }

    private class FakeGateway(
        var validation: TvSessionValidation,
    ) : TvSessionGateway {
        val calls = mutableListOf<String>()
        var linkAttempts = 0
        private val linkStates = MutableSharedFlow<DeviceCodeAuthState>()

        suspend fun emit(state: DeviceCodeAuthState) {
            linkStates.emit(state)
        }

        override fun link(): Flow<DeviceCodeAuthState> = flow {
            calls += "link"
            linkAttempts += 1
            try {
                while (true) {
                    val next = linkStates.first()
                    emit(next)
                    if (next.isTerminal()) return@flow
                }
            } catch (error: CancellationException) {
                calls += "link-cancelled"
                throw error
            }
        }

        override fun setAccessToken(accessToken: AccessToken) {
            calls += "set"
        }

        override fun clearAccessToken() {
            calls += "clear"
        }

        override suspend fun validateSession(): TvSessionValidation {
            calls += "validate"
            return validation
        }

        override suspend fun logout(): Boolean {
            calls += "logout"
            return true
        }
    }

    private companion object {
        val BUDGET = kotlin.time.Duration.parse("5m")

        fun stopped(failure: TvLinkFailure) = TvAuthState.Linking(TvLinkPhase.Stopped(TvLinkStop.Failed(failure)))

        fun DeviceCodeAuthState.isTerminal(): Boolean =
            this is DeviceCodeAuthState.Linked || this is DeviceCodeAuthState.Expired || this is DeviceCodeAuthState.Failed

        fun transportFailure(): PutioOperationException =
            PutioOperationException(
                domain = "auth",
                operation = "getCode",
                contract = null,
                reason = null,
                underlyingError = PutioTransportException(
                    PutioRequestData("GET", "https://api.put.io/v2/oauth2/oob/code"),
                    IOException("offline"),
                ),
            )

        fun accountInfo(): AccountInfo = AccountInfo(
            userId = 42,
            username = "user",
            mail = "user@example.com",
            avatarUrl = "https://example.com/avatar.png",
            disk = AccountDisk(available = 1, size = 2, used = 1),
            settings = AccountSettings(sortBy = "NAME_ASC"),
            accountStatus = "active",
        )
    }
}
