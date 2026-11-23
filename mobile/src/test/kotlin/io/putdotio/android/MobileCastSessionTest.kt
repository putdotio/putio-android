package io.putdotio.android

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.CastFixtures.Item
import io.putdotio.android.CastFixtures.Media
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.CastAvailability
import io.putdotio.android.playback.CastHandover
import io.putdotio.android.playback.CastRemoteState
import io.putdotio.android.playback.CastSignOutEnd
import io.putdotio.android.playback.CastStopCallback
import io.putdotio.android.playback.CastStopControl
import io.putdotio.android.playback.CastTransfer
import io.putdotio.android.playback.GmsCastGateway
import io.putdotio.android.playback.GmsCastRemote
import io.putdotio.android.playback.GmsCastRoutes
import io.putdotio.android.playback.MOVE_HERE_WAIT_MILLIS
import io.putdotio.android.playback.MobileCast
import io.putdotio.android.playback.MobileCastController
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.PutioCastMediaItemConverter
import io.putdotio.android.playback.releaseOnceCastEnds
import io.putdotio.android.playback.toCastMedia
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.Closeable
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
class MobileCastSessionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        MobileCast.resetForTests()
        MobileOAuthRuntime.resetForTests()
    }

    @Test
    fun castLoadsOffTheLaunchPathAndOffersNothingUntilItAnswers() {
        val load = TaskCompletionSource<CastContext>()
        var asked = 0
        val gateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote()) {
            asked++
            load.task
        }

        gateway.start()
        gateway.shown(PlayerLifecycleOwner(), true)

        assertEquals(1, asked)
        assertEquals(CastAvailability.Unsupported, gateway.availability.value)

        load.setException(IllegalStateException("Play services cannot start Cast"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(CastAvailability.Unsupported, gateway.availability.value)
    }

    @Test
    fun aRefusedLocalNetworkStaysBlockedUntilAndroidGrantsIt() {
        var denied = true
        val gateway = GmsCastGateway(app, GmsCastRoutes(app), GmsCastRemote(), localNetworkDenied = { denied }) {
            TaskCompletionSource<CastContext>().task
        }

        gateway.localNetworkAnswered()
        assertTrue(gateway.localNetworkBlocked())

        denied = false
        gateway.localNetworkAnswered()
        assertFalse(gateway.localNetworkBlocked())
    }

    @Test
    fun aSignOutEndsTheSessionCastResumesAfterIt() {
        val calls = mutableListOf<String>()
        val signOut = CastSignOutEnd { calls += "end" }

        signOut.requested()
        signOut.resumed { calls += "attach" }

        assertEquals(listOf("end", "end"), calls)
    }

    @Test
    fun aSessionStartedAfterASignOutIsTheUsersOwn() {
        val calls = mutableListOf<String>()
        val signOut = CastSignOutEnd { calls += "end" }
        signOut.requested()

        signOut.sessionStarted()
        signOut.resumed { calls += "attach" }

        assertEquals(listOf("end", "attach"), calls)
    }

    @Test
    fun aCastSessionTakesSessionAudioFromWhereItStoodAndPausesItHere() {
        val local = FakePlayer(DeviceInfo.PLAYBACK_TYPE_LOCAL, AudioItem)
        val taken = mutableListOf<Pair<MediaItem, CastHandover>>()

        CastTransfer(toReceiver = { item, handover -> taken += item to handover }, toLocal = {})
            .transferState(local, FakePlayer(DeviceInfo.PLAYBACK_TYPE_REMOTE, item = null))

        assertEquals(listOf(AudioItem to CastHandover(positionMillis = 95_000L, playing = true)), taken)
        assertFalse(local.playWhenReady)
        assertEquals("42", local.currentMediaItem?.mediaId)
        assertEquals(95_000L, local.currentPosition)
    }

    @Test
    fun aSessionThatEndsLeavesTheLocalPlayerToTheHandback() {
        val local = FakePlayer(DeviceInfo.PLAYBACK_TYPE_LOCAL, item = null)
        val taken = mutableListOf<MediaItem>()
        var handedBack = 0

        CastTransfer(toReceiver = { item, _ -> taken += item }, toLocal = { handedBack++ })
            .transferState(FakePlayer(DeviceInfo.PLAYBACK_TYPE_REMOTE, AudioItem), local)

        assertEquals(emptyList<MediaItem>(), taken)
        assertEquals(1, handedBack)
        assertEquals(0, local.mediaItemCount)
    }

    @Test
    fun theSessionSendsTheReceiverOnlyMediaMadeForItNeverALocalUrl() {
        val converter = PutioCastMediaItemConverter(app)
        val local = MediaItem.Builder()
            .setMediaId("42")
            .setUri("https://api.put.io/v2/files/42/stream?oauth_token=session-token")
            .build()

        val sent = converter.toMediaQueueItem(local).media

        assertNull(sent?.contentUrl)
        assertEquals("42", sent?.contentId)
    }

    @Test
    fun theSessionShowsTheReceiversMediaByFileAndTitleUnderTheAppNameWithoutItsUrl() {
        val converter = PutioCastMediaItemConverter(app)
        val media = requireNotNull(Source.toCastMedia(Track, "download-token"))
        val queued = converter.toMediaQueueItem(MediaItem.Builder().setUri(media.url).setTag(media).build())
        assertEquals(media.url, queued.media?.contentUrl)

        val shown = converter.toMediaItem(queued)

        assertEquals("42", shown.mediaId)
        assertEquals("track.mp3", shown.mediaMetadata.title)
        assertEquals("put.io", shown.mediaMetadata.artist)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, shown.mediaMetadata.mediaType)
        assertNull(shown.localConfiguration)
    }

    @Test
    fun theNotificationStopsCastingOnlyWhileAReceiverPlays() {
        var stops = 0
        val control = CastStopControl(app) { stops++ }

        val shown = control.buttons(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).build())
        val tapped = control.handle(requireNotNull(shown.single().sessionCommand))

        assertTrue(tapped)
        assertEquals(1, stops)
        assertEquals("Stop casting", shown.single().displayName)
        assertEquals(emptyList<CommandButton>(), control.buttons(DeviceInfo.UNKNOWN))
        assertFalse(control.handle(SessionCommand("another", Bundle.EMPTY)))
        assertEquals(1, stops)
    }

    @Test
    fun theNotificationControllerSeesTheReceiverPlayAndCanStopCasting() {
        val control = CastStopControl(app) {}
        val receiver = FakePlayer(DeviceInfo.PLAYBACK_TYPE_REMOTE, AudioItem)
        val session = MediaSession.Builder(app, receiver).setCallback(CastStopCallback(control)).build()
        try {
            val pending = MediaController.Builder(app, session.token).buildAsync()
            shadowOf(Looper.getMainLooper()).idle()
            val controller = pending.get()

            assertFalse(controller.currentTimeline.isEmpty)
            assertEquals(Player.STATE_READY, controller.playbackState)
            assertTrue(controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            assertTrue(controller.isSessionCommandAvailable(control.command))
            controller.release()
        } finally {
            session.release()
        }
    }

    @Test
    fun theSessionPlayerOutlivesItsServiceOnlyUntilTheReceiverIsGone() {
        val availability = MutableStateFlow<CastAvailability>(CastAvailability.Connected("Living room TV"))
        val casting = FakePlayer(DeviceInfo.PLAYBACK_TYPE_REMOTE, item = null)
        val idle = FakePlayer(DeviceInfo.PLAYBACK_TYPE_LOCAL, item = null)
        val scope = MainScope()

        casting.releaseOnceCastEnds(availability, scope)
        idle.releaseOnceCastEnds(MutableStateFlow(CastAvailability.Available), scope)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, casting.releases)
        assertEquals(1, idle.releases)

        availability.value = CastAvailability.Available
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, casting.releases)
        scope.cancel()
    }

    @Test
    fun theSystemOutputSwitcherCanMoveTheAudioSession() {
        val receiver = ComponentName(app, "androidx.mediarouter.media.MediaTransferReceiver")

        assertEquals(receiver.className, app.packageManager.getReceiverInfo(receiver, 0).name)
    }

    @Test
    fun sessionPlayerFallsBackToLocalAndReleasesPartialRemoteWhenCastBuilderThrows() {
        val local = FakePlayer(DeviceInfo.PLAYBACK_TYPE_LOCAL, AudioItem)
        val partialRemote = FakePlayer(DeviceInfo.PLAYBACK_TYPE_REMOTE, item = null)
        val scope = MainScope()
        MobileCast.playServices = { true }
        MobileCast.controller = MobileCastController(FakeCastGateway { 0L }, MobileCast.castScope) { 0L }
        MobileCast.createRemotePlayer = { throw IllegalStateException("Remote player failed") }
        try {
            assertSame(local, MobileCast.sessionPlayer(app, local, scope) {})

            MobileCast.createRemotePlayer = { partialRemote }
            MobileCast.createCastPlayer = { _, _, _, _, _ -> throw IllegalStateException("CastPlayer failed") }

            assertSame(local, MobileCast.sessionPlayer(app, local, scope) {})
            assertEquals(1, partialRemote.releases)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun releaseSessionPlayerReusesProcessCastScope() {
        val gateway = FakeCastGateway { 0L }.apply {
            availability.value = CastAvailability.Connected("Mi Box 4")
        }
        val local = FakePlayer(DeviceInfo.PLAYBACK_TYPE_LOCAL, AudioItem)
        val wrapper = FakePlayer(DeviceInfo.PLAYBACK_TYPE_LOCAL, item = null)
        val scope = MainScope()
        MobileCast.playServices = { true }
        MobileCast.controller = MobileCastController(gateway, MobileCast.castScope) { 0L }
        MobileCast.createRemotePlayer = { FakePlayer(DeviceInfo.PLAYBACK_TYPE_REMOTE, item = null) }
        MobileCast.createCastPlayer = { _, _, _, _, _ -> wrapper }
        try {
            val sessionPlayer = MobileCast.sessionPlayer(app, local, scope) {}
            assertSame(wrapper, sessionPlayer)
            val scopeBefore = MobileCast.castScope
            val childrenBefore = checkNotNull(scopeBefore.coroutineContext[Job]).children.count()

            MobileCast.releaseSessionPlayer(app, sessionPlayer)
            shadowOf(Looper.getMainLooper()).idle()

            assertSame(scopeBefore, MobileCast.castScope)
            assertEquals(0, wrapper.releases)
            assertEquals(childrenBefore + 1, checkNotNull(scopeBefore.coroutineContext[Job]).children.count())

            gateway.availability.value = CastAvailability.Available
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(1, wrapper.releases)
            assertEquals(childrenBefore, checkNotNull(scopeBefore.coroutineContext[Job]).children.count())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun releaseSessionPlayerReleasesLocalFallbackImmediatelyWhileCastStaysConnected() {
        val local = FakePlayer(DeviceInfo.PLAYBACK_TYPE_LOCAL, AudioItem)
        val gateway = FakeCastGateway { 0L }.apply {
            availability.value = CastAvailability.Connected("Mi Box 4")
        }
        val scope = MainScope()
        MobileCast.playServices = { true }
        MobileCast.controller = MobileCastController(gateway, MobileCast.castScope) { 0L }
        MobileCast.createRemotePlayer = { throw IllegalStateException("Cast unavailable") }
        try {
            val fallback = MobileCast.sessionPlayer(app, local, scope) {}
            assertSame(local, fallback)
            val childrenBefore = checkNotNull(MobileCast.castScope.coroutineContext[Job]).children.count()

            MobileCast.releaseSessionPlayer(app, fallback)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(1, local.releases)
            assertEquals(childrenBefore, checkNotNull(MobileCast.castScope.coroutineContext[Job]).children.count())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingProcessCastScopeClosesHeldAudioControllerWhileConnected() {
        MobileCast.resetForTests()
        val gateway = FakeCastGateway { 0L }
        var audioConnects = 0
        var audioCloses = 0
        MobileCast.playServices = { true }
        MobileCast.createGateway = { gateway }
        MobileCast.connectAudioSession = {
            audioConnects++
            Closeable { audioCloses++ }
        }
        val castScope = MobileCast.castScope
        val cast = MobileCast.controller(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(cast)

        gateway.connect()
        assertEquals(1, audioConnects)
        assertEquals(0, audioCloses)

        castScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, audioCloses)
    }

    @Test
    fun controllerReusesProcessCastScopeForBackgroundCollectors() {
        MobileCast.resetForTests()
        val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val gateway = FakeCastGateway { 0L }
        val controllerJobs = run {
            MobileCastController(gateway, probeScope) { 0L }
            checkNotNull(probeScope.coroutineContext[Job]).children.count()
        }
        probeScope.cancel()

        val finishedIds = mutableListOf<Long>()
        var audioConnects = 0
        var audioCloses = 0
        MobileCast.playServices = { true }
        MobileCast.createGateway = { gateway }
        MobileCast.onCastFinished = finishedIds::add
        MobileCast.connectAudioSession = {
            audioConnects++
            Closeable { audioCloses++ }
        }
        val castScope = MobileCast.castScope
        val cast = MobileCast.controller(app)
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(cast)
        assertSame(castScope, MobileCast.castScope)
        assertEquals(controllerJobs + 2, checkNotNull(castScope.coroutineContext[Job]).children.count())

        gateway.connect()
        requireNotNull(cast).cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Playing, positionMillis = 10_000L)
        gateway.report(CastRemoteState.Ended, positionMillis = 60_000L)
        assertEquals(1, audioConnects)
        assertEquals(listOf(42L), finishedIds)

        gateway.disconnect()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MOVE_HERE_WAIT_MILLIS + 100L))
        assertEquals(1, audioCloses)

        castScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, checkNotNull(castScope.coroutineContext[Job]).children.count())

        gateway.connect()
        gateway.report(CastRemoteState.Playing, positionMillis = 10_000L)
        gateway.report(CastRemoteState.Ended, positionMillis = 60_000L)
        assertEquals(1, audioConnects)
        assertEquals(listOf(42L), finishedIds)
    }

    private class FakePlayer(type: Int, item: MediaItem?) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var current: State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setDeviceInfo(DeviceInfo.Builder(type).build())
            .apply {
                if (item != null) {
                    setPlaylist(listOf(MediaItemData.Builder(item.mediaId).setMediaItem(item).build()))
                    setPlaybackState(STATE_READY)
                    setPlayWhenReady(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    setContentPositionMs(95_000L)
                }
            }
            .build()

        var releases = 0

        override fun getState(): State = current

        override fun handleRelease(): ListenableFuture<*> {
            releases++
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            current = current.buildUpon()
                .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            current = current.buildUpon().setPlaybackState(STATE_IDLE).build()
            return Futures.immediateVoidFuture()
        }

        override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
            current = current.buildUpon().setPlaylist(emptyList()).setPlaybackState(STATE_IDLE).build()
            return Futures.immediateVoidFuture()
        }
    }

    private companion object {
        val AudioItem: MediaItem = MediaItem.Builder()
            .setMediaId("42")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("track.mp3").build())
            .build()
        val Track = PlaybackTarget(FilesItemId(42L), "track.mp3", PlaybackMediaType.AUDIO)
        val Source = PlaybackSource(
            fileId = 42L,
            kind = PlaybackSourceKind.ORIGINAL,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/stream"),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )
    }
}
