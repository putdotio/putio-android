package io.putdotio.android

import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteDescriptor
import androidx.mediarouter.media.MediaRouteDiscoveryRequest
import androidx.mediarouter.media.MediaRouteProvider
import androidx.mediarouter.media.MediaRouteProviderDescriptor
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionTransferCallback
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.cast.CastMediaControlIntent
import io.putdotio.android.playback.CastContextHandle
import io.putdotio.android.playback.CastReceiverStore
import io.putdotio.android.playback.PUTIO_RECEIVER_APPLICATION_ID
import io.putdotio.android.playback.GmsCastGateway
import io.putdotio.android.playback.GmsCastRemote
import io.putdotio.android.playback.GmsCastRoutes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(provider.asksForRemotePlayback())

        routes.stopDiscovery()
        idle()
        assertEquals(false, provider.request?.isActiveScan)
        assertFalse(provider.asksForRemotePlayback())

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

    @Test
    fun gatewayKeepsWatchingAcrossOverlappingActivityRecreation() {
        val gateway = GmsCastGateway(app, routes, GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        val oldOwner = PlayerLifecycleOwner()
        val newOwner = PlayerLifecycleOwner()

        gateway.shown(oldOwner, true)
        idle()
        assertEquals(false, provider.request?.isActiveScan)

        gateway.shown(newOwner, true)
        idle()
        assertEquals(false, provider.request?.isActiveScan)

        gateway.shown(oldOwner, false)
        idle()
        assertEquals(false, provider.request?.isActiveScan)

        gateway.shown(oldOwner, false)
        idle()
        assertEquals(false, provider.request?.isActiveScan)

        gateway.shown(newOwner, false)
        idle()
        assertNull(provider.request)
    }

    @Test
    fun gatewayIgnoresDuplicateStartsFromOneOwner() {
        val gateway = GmsCastGateway(app, routes, GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        val owner = PlayerLifecycleOwner()

        gateway.shown(owner, true)
        gateway.shown(owner, true)
        idle()
        assertEquals(false, provider.request?.isActiveScan)

        gateway.shown(owner, false)
        idle()
        assertNull(provider.request)
    }

    @Test
    fun gatewayStartsWatchingWhenCastBindsAfterActivityRecreation() {
        val lateRoutes = GmsCastRoutes(app)
        val gateway = GmsCastGateway(app, lateRoutes, GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        val oldOwner = PlayerLifecycleOwner()
        val newOwner = PlayerLifecycleOwner()
        try {
            gateway.shown(oldOwner, true)
            gateway.shown(newOwner, true)
            gateway.shown(oldOwner, false)
            idle()
            assertNull(provider.request)

            gateway.bind(FakeCastContextHandle(SELECTOR))
            idle()
            assertEquals(false, provider.request?.isActiveScan)

            gateway.shown(newOwner, false)
            idle()
            assertNull(provider.request)
        } finally {
            lateRoutes.unwatch()
        }
    }

    @Test
    fun gatewayDoesNotWatchOnBindWhenNoOwnerIsStarted() {
        val lateRoutes = GmsCastRoutes(app)
        val gateway = GmsCastGateway(app, lateRoutes, GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        val owner = PlayerLifecycleOwner()
        try {
            gateway.shown(owner, true)
            gateway.shown(owner, false)
            gateway.bind(FakeCastContextHandle(SELECTOR))
            idle()

            assertNull(provider.request)
        } finally {
            lateRoutes.unwatch()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun aReceiverChosenBeforeCastLoadsAppliesAsItBindsAndTheSearchFollowsIt() {
        val gateway = GmsCastGateway(app, routes, GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        val cast = FakeCastContextHandle(SELECTOR, putioSelector = PUTIO_SELECTOR)
        try {
            gateway.useReceiver(putio = true)
            gateway.bind(cast)

            assertEquals(listOf(PUTIO_RECEIVER_APPLICATION_ID), cast.applied)
            assertEquals(PUTIO_SELECTOR, routes.selector)
        } finally {
            gateway.useReceiver(putio = false)
        }
    }

    @Test
    fun aReceiverChosenMidSessionWaitsForItsEnd() {
        val gateway = GmsCastGateway(app, routes, GmsCastRemote()) {
            TaskCompletionSource<CastContext>().task
        }
        val cast = FakeCastContextHandle(SELECTOR, castState = CastState.CONNECTED)
        try {
            gateway.bind(cast)
            gateway.useReceiver(putio = true)

            assertEquals(emptyList<String>(), cast.applied)
            assertTrue(CastReceiverStore.putio(app))
        } finally {
            gateway.useReceiver(putio = false)
        }
    }

    private class FakeCastContextHandle(
        private val defaultSelector: MediaRouteSelector?,
        override val castState: Int = CastState.NO_DEVICES_AVAILABLE,
        private val putioSelector: MediaRouteSelector? = defaultSelector,
    ) : CastContextHandle {
        override val currentCastSession: CastSession? = null
        private var current = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        override val receiverApplicationId: String get() = current
        val applied = mutableListOf<String>()

        override val mergedSelector: MediaRouteSelector?
            get() = if (receiverApplicationId == PUTIO_RECEIVER_APPLICATION_ID) putioSelector else defaultSelector

        override fun setReceiverApplicationId(applicationId: String) {
            applied += applicationId
            current = applicationId
        }

        override fun addCastStateListener(listener: () -> Unit) = Unit

        override fun addSessionManagerListener(listener: SessionManagerListener<CastSession>) = Unit

        override fun addSessionTransferCallback(callback: SessionTransferCallback) = Unit

        override fun endCurrentSession(stopCasting: Boolean) = Unit
    }

    private class ReceiverProvider(context: Context) : MediaRouteProvider(context) {
        var request: MediaRouteDiscoveryRequest? = null
            private set

        override fun onDiscoveryRequestChanged(request: MediaRouteDiscoveryRequest?) {
            this.request = request
        }

        fun asksForRemotePlayback(): Boolean =
            request?.selector?.hasControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK) == true

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
        val PUTIO_SELECTOR: MediaRouteSelector =
            MediaRouteSelector.Builder().addControlCategory("$CATEGORY.PUTIO").build()
    }
}
