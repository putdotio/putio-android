package io.putdotio.android.tv.auth

import io.putdotio.android.auth.AccessToken
import io.putdotio.android.auth.InMemoryAuthTokenStore
import io.putdotio.android.auth.PendingTokenRevocations
import io.putdotio.android.auth.ScriptedTokenRevoker
import io.putdotio.android.tv.watchnext.TvStoredWatchNextProgram
import io.putdotio.android.tv.watchnext.TvWatchNext
import io.putdotio.android.tv.watchnext.TvWatchNextOwner
import io.putdotio.android.tv.watchnext.TvWatchNextProgram
import io.putdotio.android.tv.watchnext.TvWatchNextStore
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import io.putdotio.sdk.account.AccountDisk
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.auth.DeviceCodeAuthState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TvAuthRuntimeTest {
    @Test
    fun offlineSearchRestoreRecoversOnceNetworkReturns() = runTest {
        withHarness(validation = TvSessionValidation.Unavailable(IOException("offline"))) { harness ->
            assertNull(harness.runtime.globalSearch.files("harbor"))
            assertEquals(TvAuthState.Initializing, harness.controller.state.value)
            assertEquals(1, harness.gateway.validateCalls)

            harness.gateway.validation = TvSessionValidation.Valid(accountInfo().toTvAccount())

            val recovered = harness.runtime.globalSearch.files("harbor")
            assertNotNull(recovered)
            assertTrue(harness.controller.state.value is TvAuthState.SignedIn)
            assertEquals(2, harness.gateway.validateCalls)
        }
    }

    @Test
    fun concurrentSearchCallersCoalesceOntoSingleRestore() = runTest {
        withHarness(validation = TvSessionValidation.Valid(accountInfo().toTvAccount())) { harness ->
            val gate = CompletableDeferred<Unit>()
            harness.gateway.validationGate = gate

            val first = async { harness.runtime.globalSearch.files("harbor") }
            val second = async { harness.runtime.globalSearch.files("beacon") }
            testScheduler.runCurrent()

            assertEquals(1, harness.gateway.validateCalls)
            gate.complete(Unit)
            advanceUntilIdle()

            assertNotNull(first.await())
            assertNotNull(second.await())
            assertEquals(1, harness.gateway.validateCalls)
        }
    }

    @Test
    fun cancelledSearchCallerLeavesRuntimeRecoverable() = runTest {
        withHarness(validation = TvSessionValidation.Unavailable(IOException("offline"))) { harness ->
            val gate = CompletableDeferred<Unit>()
            harness.gateway.validationGate = gate

            val cancelledCaller = launch { harness.runtime.globalSearch.files("harbor") }
            testScheduler.runCurrent()
            cancelledCaller.cancel()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(TvAuthState.Initializing, harness.controller.state.value)

            harness.gateway.validationGate = null
            harness.gateway.validation = TvSessionValidation.Valid(accountInfo().toTvAccount())

            val recovered = harness.runtime.globalSearch.files("harbor")
            assertNotNull(recovered)
            assertTrue(harness.controller.state.value is TvAuthState.SignedIn)
        }
    }

    private suspend fun TestScope.withHarness(
        validation: TvSessionValidation,
        block: suspend (RuntimeHarness) -> Unit,
    ) {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            block(RuntimeHarness(validation = validation, appScope = appScope))
        } finally {
            appScope.cancel()
        }
    }

    private class RuntimeHarness(
        validation: TvSessionValidation,
        appScope: CoroutineScope,
    ) {
        val gateway = FakeGateway(validation)
        val tokenStore = InMemoryAuthTokenStore(checkNotNull(AccessToken.parse("stored-token")))
        val controller = TvAuthController(
            tokenStore = tokenStore,
            sessionGateway = gateway,
            tokenRevocations = PendingTokenRevocations(
                stores = listOf(InMemoryAuthTokenStore()),
                sessionStore = tokenStore,
                revoker = ScriptedTokenRevoker(),
                scope = appScope,
            ),
            scope = appScope,
            legacySession = EmptyLegacySession,
        )
        val runtime = TvAuthRuntime(
            putioClient = PutioClient(
                PutioConfig(clientId = "1", clientName = "tv-test"),
                okHttpClient = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"files":[],"cursor":null,"total":0,"status":"OK"}""".toResponseBody(JSON))
                            .build()
                    }
                    .build(),
            ),
            authController = controller,
            applicationScope = appScope,
            watchNext = TvWatchNext(
                store = NoOpWatchNextStore,
                authState = controller.state,
                quietSignOuts = controller.quietSignOuts,
                scope = appScope,
            ),
        )
    }

    private class FakeGateway(
        var validation: TvSessionValidation,
    ) : TvSessionGateway {
        var validateCalls = 0
        var validationGate: CompletableDeferred<Unit>? = null

        override fun link(): Flow<DeviceCodeAuthState> = emptyFlow()

        override fun setAccessToken(accessToken: AccessToken) = Unit

        override fun clearAccessToken() = Unit

        override suspend fun validateSession(): TvSessionValidation {
            validateCalls += 1
            validationGate?.await()
            return validation
        }
    }

    private object EmptyLegacySession : LegacyTvSession {
        override suspend fun read(): AccessToken? = null

        override suspend fun delete() = Unit
    }

    private object NoOpWatchNextStore : TvWatchNextStore {
        override suspend fun programs(): List<TvStoredWatchNextProgram> = emptyList()

        override suspend fun insert(owner: TvWatchNextOwner, program: TvWatchNextProgram, engagedAtMillis: Long) = Unit

        override suspend fun update(
            rowId: Long,
            owner: TvWatchNextOwner,
            program: TvWatchNextProgram,
            engagedAtMillis: Long,
        ) = Unit

        override suspend fun delete(rowId: Long) = Unit
    }

    private companion object {
        val JSON = "application/json".toMediaType()

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
