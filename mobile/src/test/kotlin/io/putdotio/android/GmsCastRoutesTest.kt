package io.putdotio.android

import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import androidx.mediarouter.media.MediaRouteDescriptor
import androidx.mediarouter.media.MediaRouteDiscoveryRequest
import androidx.mediarouter.media.MediaRouteProvider
import androidx.mediarouter.media.MediaRouteProviderDescriptor
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.playback.GmsCastRoutes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GmsCastRoutesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val provider = ReceiverProvider(app)
    private val routes = GmsCastRoutes(app).also { it.selector = SELECTOR }

    @Before
    fun addProvider() {
        app.getSystemService(Context.MEDIA_ROUTER_SERVICE)
        MediaRouter.getInstance(app).addProvider(provider)
        idle()
    }

    @After
    fun removeProvider() {
        routes.stopDiscovery()
        routes.unwatch()
        MediaRouter.getInstance(app).removeProvider(provider)
        idle()
    }

    @Test
    fun watchingSearchesPassivelyAndThePickerScansActivelyOnTop() {
        routes.watch()
        idle()
        assertEquals(false, provider.request?.isActiveScan)

        routes.startDiscovery()
        idle()
        assertEquals(true, provider.request?.isActiveScan)

        routes.stopDiscovery()
        idle()
        assertEquals(false, provider.request?.isActiveScan)

        routes.unwatch()
        idle()
        assertNull(provider.request)
    }

    @Test
    fun aReceiverFoundWhileWatchingReachesTheDevicesAndTheGateway() {
        var changes = 0
        routes.onDevicesChanged = { changes++ }
        routes.watch()
        idle()
        val before = changes

        provider.publish("Living room")
        idle()

        assertEquals(listOf("Living room"), routes.devices.value.map { it.name })
        assertTrue(changes > before)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class ReceiverProvider(context: Context) : MediaRouteProvider(context) {
        var request: MediaRouteDiscoveryRequest? = null
            private set

        override fun onDiscoveryRequestChanged(request: MediaRouteDiscoveryRequest?) {
            this.request = request
        }

        fun publish(name: String) {
            val route = MediaRouteDescriptor.Builder(name, name)
                .addControlFilter(IntentFilter().apply { addCategory(CATEGORY) })
                .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                .setEnabled(true)
                .build()
            descriptor = MediaRouteProviderDescriptor.Builder().addRoute(route).build()
        }
    }

    private companion object {
        const val CATEGORY = "io.putdotio.android.test.CAST_RECEIVER"
        val SELECTOR: MediaRouteSelector = MediaRouteSelector.Builder().addControlCategory(CATEGORY).build()
    }
}
