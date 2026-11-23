package io.putdotio.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [35],
    qualifiers = "en-rUS",
    shadows = [MobileRetryWhenOnlineTest.ThrowingConnectivityManagerShadow::class],
)
class MobileRetryWhenOnlineTest {
    @get:Rule
    val compose = createComposeRule()

    private val owner = TestLifecycleOwner()
    private var retries = 0

    @Before
    @After
    fun clearShadowFailures() {
        ThrowingConnectivityManagerShadow.reset()
    }

    @Test
    fun aStoppedAppRetriesNothingUntilItIsStartedAgain() {
        network(validated = false)
        showRetry()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

        compose.mainClock.advanceTimeBy(RETRY_CLOCK_MILLIS * 3)
        compose.runOnIdle { assertEquals(0, retries) }

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(0, retries) }
        compose.mainClock.advanceTimeBy(RETRY_CLOCK_MILLIS)

        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun aNetworkBackWhileStoppedRetriesAtOnceOnReturn() {
        network(validated = false)
        showRetry()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

        network(validated = true)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }

        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun unregisterSecurityExceptionOnStopDoesNotCrashAndResetsWaiting() {
        network(validated = false)
        showRetry()

        ThrowingConnectivityManagerShadow.throwOnUnregister = SecurityException("b/175055271")
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

        ThrowingConnectivityManagerShadow.throwOnUnregister = null
        network(validated = true)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }

        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun registerAndCapabilitiesSecurityExceptionsDoNotCrashAndClockStillRetries() {
        ThrowingConnectivityManagerShadow.throwOnRegister = SecurityException("b/175055271")
        ThrowingConnectivityManagerShadow.throwOnGetCapabilities = SecurityException("b/175055271")
        showRetry()

        compose.mainClock.advanceTimeBy(RETRY_CLOCK_MILLIS)

        compose.runOnIdle { assertEquals(1, retries) }
    }

    private fun showRetry() {
        compose.setBuiltContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RetryWhenOnline(failure = "offline") { retries++ }
            }
        }
    }

    private fun network(validated: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        if (validated) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry
    }

    @Implements(ConnectivityManager::class)
    class ThrowingConnectivityManagerShadow : ShadowConnectivityManager() {
        @Implementation(minSdk = 24)
        public override fun registerDefaultNetworkCallback(
            networkCallback: ConnectivityManager.NetworkCallback,
        ) {
            throwOnRegister?.let { throw it }
            super.registerDefaultNetworkCallback(networkCallback)
        }

        @Implementation
        public override fun unregisterNetworkCallback(
            networkCallback: ConnectivityManager.NetworkCallback,
        ) {
            throwOnUnregister?.let { throw it }
            super.unregisterNetworkCallback(networkCallback)
        }

        @Implementation
        public override fun getActiveNetwork(): Network? {
            throwOnActiveNetwork?.let { throw it }
            return super.getActiveNetwork()
        }

        @Implementation
        public override fun getNetworkCapabilities(network: Network?): NetworkCapabilities? {
            throwOnGetCapabilities?.let { throw it }
            return super.getNetworkCapabilities(network)
        }

        companion object {
            var throwOnRegister: Throwable? = null
            var throwOnUnregister: Throwable? = null
            var throwOnActiveNetwork: Throwable? = null
            var throwOnGetCapabilities: Throwable? = null

            fun reset() {
                throwOnRegister = null
                throwOnUnregister = null
                throwOnActiveNetwork = null
                throwOnGetCapabilities = null
            }
        }
    }
}
