package io.putdotio.android.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [NetworkReturnsTest.ThrowingConnectivityManagerShadow::class])
class NetworkReturnsTest {
    @Before
    @After
    fun clearShadowFailures() {
        ThrowingConnectivityManagerShadow.reset()
    }

    @Test
    fun watchNetworkReturnsIgnoresSecurityExceptionOnRegister() {
        ThrowingConnectivityManagerShadow.throwOnRegister = SecurityException("b/175055271")
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        var returns = 0

        watchNetworkReturns(context) { returns += 1 }

        assertEquals(0, returns)
        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
    }

    @Test
    fun watchNetworkReturnsIgnoresSecurityExceptionOnActiveNetworkAndStillNotifiesOnReturn() {
        ThrowingConnectivityManagerShadow.throwOnActiveNetwork = SecurityException("b/175055271")
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        var validated = 0
        var returns = 0

        watchNetworkReturns(context, onValidated = { validated += 1 }) { returns += 1 }

        assertEquals(0, validated)
        assertEquals(0, returns)

        ThrowingConnectivityManagerShadow.throwOnActiveNetwork = null
        val network = requireNotNull(connectivity.activeNetwork)
        val callback = shadowOf(connectivity).networkCallbacks.single()
        callback.onCapabilitiesChanged(network, validatedCapabilities())

        assertEquals(1, validated)
        assertEquals(1, returns)
    }

    @Test
    fun watchNetworkReturnsIgnoresSecurityExceptionOnGetNetworkCapabilitiesAndStillNotifiesOnReturn() {
        ThrowingConnectivityManagerShadow.throwOnGetCapabilities = SecurityException("b/175055271")
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        var returns = 0

        watchNetworkReturns(context) { returns += 1 }

        assertEquals(0, returns)

        ThrowingConnectivityManagerShadow.throwOnGetCapabilities = null
        val network = requireNotNull(connectivity.activeNetwork)
        val callback = shadowOf(connectivity).networkCallbacks.single()
        callback.onCapabilitiesChanged(network, validatedCapabilities())

        assertEquals(1, returns)
    }

    private fun validatedCapabilities(): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { capabilities ->
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
            var throwOnActiveNetwork: Throwable? = null
            var throwOnGetCapabilities: Throwable? = null

            fun reset() {
                throwOnRegister = null
                throwOnActiveNetwork = null
                throwOnGetCapabilities = null
            }
        }
    }
}
