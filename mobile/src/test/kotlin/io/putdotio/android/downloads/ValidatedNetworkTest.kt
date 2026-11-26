package io.putdotio.android.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ValidatedNetworkTest.ThrowingConnectivityManagerShadow::class])
class ValidatedNetworkTest {
    @Before
    @After
    fun resetShadow() {
        ThrowingConnectivityManagerShadow.reset()
    }

    @Test
    fun validatedNetworkIgnoresSecurityExceptionOnRegisterAndSkipsUnregister() = runBlocking {
        ThrowingConnectivityManagerShadow.throwOnRegister = SecurityException("b/175055271")
        val context: Context = ApplicationProvider.getApplicationContext()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)

        val emissions = withTimeout(5_000L) { validatedNetwork(context).toList() }

        assertEquals(listOf(false), emissions)
        assertEquals(0, ThrowingConnectivityManagerShadow.unregisterCalls)
        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
    }

    @Test
    fun validatedNetworkTracksAvailabilityAndGuardsUnregisterOnCancel() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = requireNotNull(connectivity.activeNetwork)
        val emissions = mutableListOf<Boolean>()

        ThrowingConnectivityManagerShadow.throwOnUnregister = SecurityException("b/175055271")
        withTimeout(5_000L) {
            val collector = launch {
                validatedNetwork(context).take(3).toList(emissions)
            }
            while (shadowOf(connectivity).networkCallbacks.isEmpty()) {
                kotlinx.coroutines.yield()
            }
            val callback = shadowOf(connectivity).networkCallbacks.single()
            callback.onAvailable(network)
            callback.onLost(network)
            collector.join()
        }

        assertEquals(listOf(false, true, false), emissions)
        assertEquals(1, ThrowingConnectivityManagerShadow.unregisterCalls)
    }

    @Implements(ConnectivityManager::class)
    class ThrowingConnectivityManagerShadow : ShadowConnectivityManager() {
        @Implementation
        public override fun registerNetworkCallback(
            request: NetworkRequest,
            networkCallback: ConnectivityManager.NetworkCallback,
        ) {
            throwOnRegister?.let { throw it }
            super.registerNetworkCallback(request, networkCallback)
        }

        @Implementation
        public override fun unregisterNetworkCallback(
            networkCallback: ConnectivityManager.NetworkCallback,
        ) {
            unregisterCalls += 1
            throwOnUnregister?.let { throw it }
            super.unregisterNetworkCallback(networkCallback)
        }

        companion object {
            var throwOnRegister: Throwable? = null
            var throwOnUnregister: Throwable? = null
            var unregisterCalls: Int = 0

            fun reset() {
                throwOnRegister = null
                throwOnUnregister = null
                unregisterCalls = 0
            }
        }
    }
}
