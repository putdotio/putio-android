package io.putdotio.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.auth.AccessToken
import io.putdotio.android.auth.AuthTokenStore
import io.putdotio.android.auth.MobileAuthController
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthConfiguration
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.auth.MobileSignedOutReason
import io.putdotio.android.auth.PendingOAuthAttempt
import io.putdotio.android.auth.PendingOAuthAttemptStore
import io.putdotio.android.auth.PutioAuthSessionGateway
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.trash.SdkTrashRepository
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileTrashSessionIntegrationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun trashList401ExpiresTheAuthenticatedRootSession() = withRoot(listStatus = 401) { fixture, _ ->
        openTrash()
        awaitExpiredSession(fixture)
    }

    @Test
    fun pendingRestoreCheck401ExpiresTheAuthenticatedRootSession() = withRoot { fixture, check ->
        openTrash()
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithText("deleted.txt").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Actions for deleted.txt").performClick()
        compose.onNodeWithTag(MOBILE_TRASH_ITEM_RESTORE_TAG).performClick()
        compose.onNodeWithTag(MOBILE_TRASH_CONFIRM_TAG).performClick()
        // The accepted restore runs its own check, which the fixture answers with 404.
        // Wait for that response to be served and applied, then flip the fixture to 401
        // and keep clicking until the click is accepted: `startCheck()` drops a click while
        // a request is in flight, and the enabled button can lag the state by a frame.
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithText("Not available in Files yet. Check status again in a moment.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(fixture.authController.state.value is MobileAuthState.SignedIn)
        check.status.set(401)
        compose.waitUntil(5_000L) {
            // Check completion first: an accepted click may already have expired the session and
            // removed the button, so clicking unconditionally would fail on a success path.
            if (compose.runOnIdle { check.served.get() >= 2 }) return@waitUntil true
            compose.onAllNodesWithTag(MOBILE_TRASH_CHECK_TAG).fetchSemanticsNodes().firstOrNull() ?: return@waitUntil false
            compose.onNodeWithTag(MOBILE_TRASH_CHECK_TAG).performClick()
            compose.waitForIdle()
            compose.runOnIdle { check.served.get() >= 2 }
        }
        awaitExpiredSession(fixture)
    }

    @Test
    fun trashList403ShowsPermissionFailureAndKeepsTheAuthenticatedSession() = withRoot(listStatus = 403) { fixture, _ ->
        openTrash()
        val forbidden = RuntimeEnvironment.getApplication().getString(R.string.mobile_trash_access_denied)
        compose.waitUntil(5_000L) { compose.onAllNodesWithText(forbidden).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(forbidden).assertIsDisplayed()
        compose.waitForIdle()
        assertTrue(fixture.authController.state.value is MobileAuthState.SignedIn)
        assertEquals("synthetic-token", runBlocking { fixture.tokenStore.read()?.reveal() })
    }

    private fun openTrash() {
        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithTag(MOBILE_MANAGE_TRASH_TAG).performScrollTo().performClick()
    }

    private fun awaitExpiredSession(fixture: TrashSessionRootFixture) {
        try {
            compose.waitUntil(5_000L) {
                compose.runOnIdle { fixture.authController.state.value is MobileAuthState.SignedOut }
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(compose.runOnIdle { fixture.sessionFailureDiagnostic() }, timeout)
        }
        assertEquals(
            MobileAuthState.SignedOut(MobileSignedOutReason.SessionExpired), fixture.authController.state.value,
        )
        assertNull(runBlocking { fixture.tokenStore.read() })
    }

    /** The `/files/7` check endpoint: the status to serve next and how many times it has answered. */
    private class CheckEndpoint {
        val status = AtomicInteger(404)
        val served = AtomicInteger(0)
    }

    private fun withRoot(
        listStatus: Int = 200,
        block: (TrashSessionRootFixture, CheckEndpoint) -> Unit,
    ) {
        val check = CheckEndpoint()
        PlaybackConfigHttpFixture { method, path ->
            when ("$method $path") {
                "GET /v2/oauth2/validate" -> 200 to """{"status":"OK","result":true}"""
                "GET /v2/trash/list" -> if (listStatus == 200) 200 to TRASH_PAGE else errorResponse(listStatus)
                "POST /v2/trash/restore" -> 200 to """{"status":"OK"}"""
                "GET /v2/files/7" -> errorResponse(check.status.get()).also { check.served.incrementAndGet() }
                else -> null
            }
        }.use { server ->
            PutioClient(PutioConfig(accessToken = "synthetic-token", baseUrl = server.baseUrl)).use { client ->
                TrashSessionRootFixture(client).use { fixture ->
                    runBlocking { withTimeout(5_000L) { fixture.authController.restoreSession() } }
                    assertTrue(fixture.authController.state.value is MobileAuthState.SignedIn)
                    var mounted by mutableStateOf(true)
                    try {
                        compose.setContent { if (mounted) fixture.Content() }
                        block(fixture, check)
                        assertEquals(emptyList<String>(), server.unexpectedRequests.toList())
                    } finally {
                        compose.runOnIdle { mounted = false }
                        compose.waitForIdle()
                    }
                }
            }
        }
    }

    private companion object {
        const val TRASH_PAGE = """{"status":"OK","files":[{
            "id":7,"name":"deleted.txt","parent_id":0,"size":12,"file_type":"TEXT",
            "created_at":"2026-09-01T00:00:00Z","deleted_at":"2026-09-06T00:00:00Z",
            "expiration_date":"2026-09-20T00:00:00Z"}],"cursor":null,"total":1,"trash_size":12}"""

        fun errorResponse(status: Int): Pair<Int, String> =
            status to """{"status":"ERROR","error_type":"TEST_FAILURE","status_code":$status}"""
    }
}

private class TrashSessionRootFixture(client: PutioClient) : Closeable {
    val tokenStore = object : AuthTokenStore {
        private var token = AccessToken.parse("synthetic-token")
        override suspend fun read(): AccessToken? = token
        override suspend fun write(accessToken: AccessToken) { token = accessToken }
        override suspend fun clear() { token = null }
    }
    val authController = MobileAuthController(
        oauthConfiguration = MobileOAuthConfiguration.fromClientId("9677"),
        tokenStore = tokenStore,
        pendingOAuthAttemptStore = object : PendingOAuthAttemptStore {
            override suspend fun read(): PendingOAuthAttempt? = null
            override suspend fun write(attempt: PendingOAuthAttempt) = error("No OAuth launch expected")
            override suspend fun clear() = Unit
        },
        sessionGateway = PutioAuthSessionGateway(client),
    )
    private val viewModels = ViewModelStore()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + job)
    private val runtime = MobileOAuthRuntime(client, authController, scope)
    private val files = own(MobileFilesViewModel(authController.state))
    private val settings = own(MobileAccountSettingsViewModel(authController.state))
    private val appConfig = own(MobileAndroidAppConfigViewModel(authController.state))
    private val search = own(MobileSearchHistoryViewModel(RuntimeEnvironment.getApplication(), authController.state))
    private val transfers = own(MobileTransfersViewModel(authController.state))
    private val trash = own(MobileTrashViewModel(authController.state))

    fun sessionFailureDiagnostic(): String {
        val auth = authController.state.value
        val signedIn = auth as? MobileAuthState.SignedIn
        val state = signedIn?.let {
            trash.controllerFor(it.account.userId, it.sessionId, SdkTrashRepository(runtime.putioClient))?.state?.value
        }
        return "Expected session expiry; auth=${auth.javaClass.simpleName}; " +
            "trash=${state?.content?.javaClass?.simpleName}; " +
            "authFailure=${state?.authenticationFailure?.javaClass?.simpleName}; " +
            "check=${state?.restoreOutcome?.check}; " +
            "checkFailure=${state?.restoreOutcome?.checkFailure?.javaClass?.simpleName}"
    }

    @Composable
    fun Content() {
        val state by authController.state.collectAsState()
        val signedIn = state as? MobileAuthState.SignedIn ?: return
        PutioTheme {
            SignedInMobileRoot(
                runtime = runtime, signedIn = signedIn, filesViewModel = files,
                accountSettingsViewModel = settings, appConfigViewModel = appConfig,
                searchHistoryViewModel = search, transfersViewModel = transfers,
                trashViewModel = trash, authController = authController, rootScope = scope,
            )
        }
    }

    private fun <T : ViewModel> own(viewModel: T): T =
        viewModel.also { viewModels.put(it.javaClass.name, it) }

    override fun close() {
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModels.clear() }
        } finally {
            runBlocking { withTimeout(2_000L) { job.cancelAndJoin() } }
        }
    }
}
