package io.putdotio.android

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.playback.DefaultMobilePlayerFactory
import io.putdotio.android.playback.MobileCast
import io.putdotio.android.playback.PlaybackMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
class MobilePlaybackServiceTest {
    @After
    fun tearDown() {
        MobilePlaybackService.createLocalPlayer = {
            DefaultMobilePlayerFactory.create(it, PlaybackMediaType.AUDIO)
        }
        MobilePlaybackService.createSessionPlayer = MobileCast::sessionPlayer
        MobilePlaybackService.releaseSessionPlayer = MobileCast::releaseSessionPlayer
        MobileOAuthRuntime.resetForTests()
    }

    @Test
    fun swipingTheTaskAwayOnlyEndsInactiveAudio() {
        assertFalse(playbackStopsWithTask(playWhenReady = true, Media3Player.STATE_READY, mediaItemCount = 1))
        assertFalse(playbackStopsWithTask(playWhenReady = true, Media3Player.STATE_BUFFERING, mediaItemCount = 1))
        assertTrue(playbackStopsWithTask(playWhenReady = false, Media3Player.STATE_READY, mediaItemCount = 1))
        assertTrue(playbackStopsWithTask(playWhenReady = true, Media3Player.STATE_ENDED, mediaItemCount = 1))
        assertTrue(playbackStopsWithTask(playWhenReady = true, Media3Player.STATE_IDLE, mediaItemCount = 1))
        assertTrue(playbackStopsWithTask(playWhenReady = true, Media3Player.STATE_READY, mediaItemCount = 0))
    }

    @Test
    fun taskRemovalFollowsSessionPlayerInsteadOfLocalPlayer() {
        val local = TrackingSessionPlayer(DeviceInfo.PLAYBACK_TYPE_LOCAL, item = AudioItem)
        val remote = TrackingSessionPlayer(DeviceInfo.PLAYBACK_TYPE_REMOTE, item = AudioItem)
        MobilePlaybackService.createLocalPlayer = { local }
        MobilePlaybackService.createSessionPlayer = { _, _, _, _ -> remote }

        val controller = Robolectric.buildService(MobilePlaybackService::class.java).create()
        try {
            val service = controller.get()
            service.onTaskRemoved(null)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(shadowOf(service).isStoppedBySelf)

            local.clearToIdle()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(shadowOf(service).isStoppedBySelf)

            remote.setPlayWhenReadyAndNotify(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun destroyReleasesMediaSessionBeforeSessionPlayer() {
        val events = mutableListOf<String>()
        val local = ListenerCountingPlayer(TrackingSessionPlayer(DeviceInfo.PLAYBACK_TYPE_LOCAL, AudioItem))
        val rawSession = TrackingSessionPlayer(
            DeviceInfo.PLAYBACK_TYPE_REMOTE,
            AudioItem,
            onRelease = { events += "player-release" },
        )
        val session = ListenerCountingPlayer(rawSession)
        val trackedSession = object : ForwardingPlayer(session) {
            override fun addListener(listener: Media3Player.Listener) = session.addListener(listener)

            override fun removeListener(listener: Media3Player.Listener) {
                session.removeListener(listener)
                events += "session-removeListener(remaining=${session.attachedListeners})"
            }
        }
        MobilePlaybackService.createLocalPlayer = { local }
        MobilePlaybackService.createSessionPlayer = { _, _, _, _ -> trackedSession }
        MobilePlaybackService.releaseSessionPlayer = { _, player ->
            events += "release-session-player(listeners=${session.attachedListeners})"
            player.release()
        }

        val controller = Robolectric.buildService(MobilePlaybackService::class.java).create()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.attachedListeners >= 2)
        val localListenersDuringRun = local.attachedListeners

        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()

        val releaseCallIndex = events.indexOf("release-session-player(listeners=0)")
        val playerReleaseIndex = events.indexOf("player-release")
        assertTrue("Expected zero listeners before releaseSessionPlayer; events=$events", releaseCallIndex >= 0)
        assertTrue(
            "Expected player.release after releaseSessionPlayer; events=$events",
            playerReleaseIndex > releaseCallIndex,
        )
        assertEquals(0, session.attachedListeners)
        assertEquals(0, local.attachedListeners)
        assertTrue(localListenersDuringRun >= 1)
    }

    @Test
    fun destroyDetachesServiceListenersEvenWhenRemoteReleaseIsDeferred() {
        val local = ListenerCountingPlayer(TrackingSessionPlayer(DeviceInfo.PLAYBACK_TYPE_LOCAL, AudioItem))
        val session = ListenerCountingPlayer(TrackingSessionPlayer(DeviceInfo.PLAYBACK_TYPE_REMOTE, AudioItem))
        var deferred: Media3Player? = null
        MobilePlaybackService.createLocalPlayer = { local }
        MobilePlaybackService.createSessionPlayer = { _, _, _, _ -> session }
        MobilePlaybackService.releaseSessionPlayer = { _, player -> deferred = player }

        val controller = Robolectric.buildService(MobilePlaybackService::class.java).create()
        shadowOf(Looper.getMainLooper()).idle()
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(deferred === session)
        assertEquals(0, session.attachedListeners)
        assertEquals(0, local.attachedListeners)
    }

    @Test
    fun coldServiceStartTriggersSessionRestoration() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val local = TrackingSessionPlayer(DeviceInfo.PLAYBACK_TYPE_LOCAL, AudioItem)
        val session = TrackingSessionPlayer(DeviceInfo.PLAYBACK_TYPE_LOCAL, AudioItem)
        MobilePlaybackService.createLocalPlayer = { local }
        MobilePlaybackService.createSessionPlayer = { _, _, _, _ -> session }

        val controller = Robolectric.buildService(MobilePlaybackService::class.java).create()
        try {
            val authState = MobileOAuthRuntime.get(app).authController.state
            val deadline = SystemClock.uptimeMillis() + RESTORE_WAIT_MILLIS
            while (authState.value is MobileAuthState.Initializing && SystemClock.uptimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(POLL_STEP_MILLIS)
            }
            assertFalse(authState.value is MobileAuthState.Initializing)
        } finally {
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class TrackingSessionPlayer(
        type: Int,
        item: MediaItem? = null,
        private val onRelease: () -> Unit = {},
    ) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var current: State = State.Builder()
            .setAvailableCommands(Media3Player.Commands.Builder().addAllCommands().build())
            .setDeviceInfo(DeviceInfo.Builder(type).build())
            .apply {
                if (item != null) {
                    setPlaylist(listOf(MediaItemData.Builder(item.mediaId).setMediaItem(item).build()))
                    setPlaybackState(STATE_READY)
                    setPlayWhenReady(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                }
            }
            .build()

        override fun getState(): State = current

        fun clearToIdle() {
            current = current.buildUpon().setPlaylist(emptyList()).setPlaybackState(STATE_IDLE).build()
            invalidateState()
        }

        fun setPlayWhenReadyAndNotify(playWhenReady: Boolean) {
            current = current.buildUpon()
                .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
            invalidateState()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            setPlayWhenReadyAndNotify(playWhenReady)
            return Futures.immediateVoidFuture()
        }

        override fun handleRelease(): ListenableFuture<*> {
            onRelease()
            return Futures.immediateVoidFuture()
        }
    }

    private companion object {
        val AudioItem: MediaItem = MediaItem.Builder().setMediaId("42").build()
        const val RESTORE_WAIT_MILLIS = 2_000L
        const val POLL_STEP_MILLIS = 10L
    }
}
