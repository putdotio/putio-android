package io.putdotio.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileRetryWhenOnlineTest {
    @get:Rule
    val compose = createComposeRule()

    private val owner = TestLifecycleOwner()
    private var retries = 0

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

    private fun showRetry() {
        compose.setContent {
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
}
