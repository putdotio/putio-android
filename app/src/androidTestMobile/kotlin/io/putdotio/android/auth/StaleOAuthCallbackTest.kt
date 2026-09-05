package io.putdotio.android.auth

import androidx.browser.auth.AuthTabIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.BuildConfig
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaleOAuthCallbackTest {
    @Test
    fun staleCallbackPreservesNewerAttemptAndMatchingMalformedCallbackConsumesIt() = runBlocking {
        val context = IsolatedAuthTestContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val tokenStore = KeystoreAuthTokenStore(context)
        val pendingStore = SharedPreferencesPendingOAuthAttemptStore(context)

        val configuration = MobileOAuthConfiguration.fromClientId(BuildConfig.PUTIO_MOBILE_OAUTH_CLIENT_ID)
        check(configuration is MobileOAuthConfiguration.Configured)
        val client = PutioClient(
            PutioConfig(clientId = configuration.clientId, clientName = MOBILE_OAUTH_CLIENT_NAME),
        )
        val controller = MobileAuthController(
            oauthConfiguration = configuration,
            tokenStore = tokenStore,
            pendingOAuthAttemptStore = pendingStore,
            sessionGateway = PutioAuthSessionGateway(client),
        )
        val runtimeJob = SupervisorJob()
        val runtimeFailure = AtomicReference<Exception>()
        val runtime = MobileOAuthRuntime(
            putioClient = client,
            authController = controller,
            applicationScope = CoroutineScope(runtimeJob + Dispatchers.Main.immediate),
            failureReporter = OAuthRuntimeFailureReporter { runtimeFailure.set(it) },
        )
        try {
            controller.restoreSession()
            assertTrue(controller.beginSignIn() is OAuthLaunchResult.Ready)
            val olderAttempt = requireNotNull(pendingStore.read())
            assertTrue(controller.cancelSignIn())
            assertTrue(controller.beginSignIn() is OAuthLaunchResult.Ready)
            val newerAttempt = requireNotNull(pendingStore.read())
            assertNotEquals(olderAttempt.state, newerAttempt.state)

            runtime.dispatchAuthTabResult(
                AuthTabIntent.RESULT_OK,
                "putio://auth#state=${olderAttempt.state}&access_token=synthetic-stale-token",
            )
            withTimeout(CALLBACK_TIMEOUT_MILLIS) { runtimeJob.children.toList().joinAll() }

            assertNull(runtimeFailure.get())
            assertEquals(newerAttempt, pendingStore.read())
            assertEquals(MobileAuthState.AwaitingOAuthCallback, controller.state.value)
            assertNull(tokenStore.read())

            runtime.dispatchAuthTabResult(
                AuthTabIntent.RESULT_OK,
                "putio://auth#state=${newerAttempt.state}&access_token=",
            )
            withTimeout(CALLBACK_TIMEOUT_MILLIS) { runtimeJob.children.toList().joinAll() }

            assertNull(runtimeFailure.get())
            assertNull(pendingStore.read())
            assertNull(tokenStore.read())
            assertEquals(
                MobileAuthState.SignedOut(MobileSignedOutReason.SignInFailed),
                controller.state.value,
            )
        } finally {
            runtimeJob.cancelAndJoin()
            try {
                pendingStore.clear()
            } finally {
                try {
                    tokenStore.clear()
                } finally {
                    context.deleteAuthPreferences()
                }
            }
        }
    }

    private companion object {
        const val CALLBACK_TIMEOUT_MILLIS = 10_000L
    }
}
