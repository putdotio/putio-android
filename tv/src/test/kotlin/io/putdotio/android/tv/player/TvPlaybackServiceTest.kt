package io.putdotio.android.tv.player

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.telemetry.TelemetryFailure
import io.putdotio.android.tv.TvSessionKey
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlaybackServiceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() = TvPlaybackService.resetForTests()

    @Test
    fun coldMediaButtonStartWithoutHostedSessionCallsStartForegroundBeforeStopping() {
        val controller = Robolectric.buildService(
            TvPlaybackService::class.java,
            Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
                Intent.EXTRA_KEY_EVENT,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            ),
        )
        val service = controller.create().get()
        try {
            val shadow = shadowOf(service)
            assertFalse(
                "onCreate must not stopSelf before onStartCommand runs startForeground",
                shadow.isStoppedBySelf,
            )

            controller.startCommand(0, 0)
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(shadow.lastForegroundNotificationId != 0)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.foregroundServiceType)
            assertTrue(shadow.isForegroundStopped)
            assertTrue(shadow.isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun swipingTheTaskAwayKeepsActiveAudioPlayingAndStopsPausedOrIdleAudio() {
        val player = FakePlayer().apply {
            setMediaItem(MediaItem.Builder().setMediaId("9").build())
            playWhenReady = true
            ready()
        }
        assertFalse(tvPlaybackStopsWithTask(player))
        player.buffer()
        assertFalse(tvPlaybackStopsWithTask(player))
        player.playWhenReady = false
        assertTrue(tvPlaybackStopsWithTask(player))
        player.playWhenReady = true
        player.end()
        assertTrue(tvPlaybackStopsWithTask(player))
        assertTrue(tvPlaybackStopsWithTask(null))

        val playing = FakePlayer().apply {
            setMediaItem(MediaItem.Builder().setMediaId("10").build())
            playWhenReady = true
            ready()
        }
        val session = tvMediaSession(compose.activity, playing).build()
        val hosted = TvBackgroundSessions.host(compose.activity, session)
        val controller = Robolectric.buildService(TvPlaybackService::class.java).create()
        try {
            val service = controller.get()
            service.onTaskRemoved(null)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(shadowOf(service).isStoppedBySelf)

            playing.pause()
            shadowOf(Looper.getMainLooper()).idle()
            service.onTaskRemoved(null)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
            hosted.close()
        }
    }

    @Test
    fun backgroundStartIllegalStateExceptionIsReportedThroughTelemetry() {
        val captured = mutableListOf<TelemetryFailure>()
        TvBackgroundSessions.reportStartFailure = { captured += it }
        val blockingContext = object : ContextWrapper(compose.activity) {
            override fun startService(service: Intent?): ComponentName? {
                error("Not allowed to start service while app is in background")
            }
        }
        val session = tvMediaSession(compose.activity, FakePlayer()).build()
        val hosted = TvBackgroundSessions.host(blockingContext, session)
        try {
            val failure = captured.single()
            assertEquals("background_start_not_allowed", failure.code)
            assertEquals(mapOf("surface" to "tv_audio"), failure.tags)
        } finally {
            hosted.close()
        }
    }

    @Test
    fun destroyingPlaybackRouteKeepsAudioAdvancingAndReattachesSingleSession() {
        val second = PlaybackTarget(FilesItemId(11), "02 Middle.mp3", PlaybackMediaType.AUDIO)
        val third = PlaybackTarget(FilesItemId(12), "03 Closing.mp3", PlaybackMediaType.AUDIO)
        val players = mutableListOf<FakePlayer>()
        val sessions = mutableListOf<MediaSession>()
        var publishCalls = 0
        val factory = singleSessionFactory(players, sessions) { publishCalls += 1 }
        TvPlaybackService.audioHost.playerFactory = factory
        val controller = startHostedAudio(second, third)
        var mounted by mutableStateOf(true)
        var owner by mutableStateOf(stoppableOwner())
        compose.setTvContent {
            if (mounted) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    TvPlaybackRoute(
                        controller = controller,
                        onExit = {},
                        onSessionRejected = {},
                        playerFactory = factory,
                        onBackground = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(second.name).assertIsDisplayed()
        assertEquals(1, publishCalls)
        assertEquals(1, sessions.size)

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()

        compose.runOnIdle { players.last().end() }
        shadowOf(Looper.getMainLooper()).idle()
        compose.runOnIdle {
            assertEquals(1, publishCalls)
            assertEquals(1, sessions.size)
            assertEquals(2, players.size)
            assertTrue(players.first().released)
            assertEquals("12", players.last().currentMediaItem?.mediaId)
            assertEquals("12", sessions.single().player.currentMediaItem?.mediaId)
            assertTrue(players.last().playWhenReady)
        }

        compose.runOnIdle {
            owner = stoppableOwner()
            mounted = true
        }
        compose.waitForIdle()
        compose.onNodeWithText(third.name).assertIsDisplayed()
        assertEquals(1, publishCalls)
        assertEquals(2, players.size)
    }

    private fun startHostedAudio(first: PlaybackTarget, second: PlaybackTarget) =
        TvPlaybackService.audioHost.start(
            TvAudioStartRequest(
                identity = TvAudioSessionIdentity(
                    sessionKey = TvSessionKey(42L, TvAuthSessionId(1L)),
                    authState = MutableStateFlow(
                        TvAuthState.SignedIn(
                            TvAccount(userId = 42L, username = "u", email = "u@example.com", historyEnabled = true),
                            TvAuthSessionId(1L),
                        ),
                    ),
                    application = compose.activity.application,
                ),
                media = TvAudioMedia(
                    target = first,
                    startItem = FilesItem(
                        id = first.fileId,
                        parentId = FilesItemId(0L),
                        name = first.name,
                        type = PutioFileType.AUDIO,
                        sizeBytes = 1L,
                        createdAt = "2026-04-20T10:00:00Z",
                    ),
                    fromHome = false,
                    startAnswer = PlaybackEvent.Resume,
                ),
                callbacks = TvAudioSessionCallbacks(
                    playbackRepository = queueRepository(first, second),
                    settings = MutableStateFlow(AccountSettingsReducer.start().state),
                    writePlaybackPosition = { _, _ -> PlaybackRepositoryResult.Success(Unit) },
                    onSaved = { _, _ -> },
                    onAuthenticationRejected = {},
                ),
            ),
        )

    private fun singleSessionFactory(
        players: MutableList<FakePlayer>,
        sessions: MutableList<MediaSession>,
        onPublish: () -> Unit,
    ) = object : TvPlayerFactory {
        override fun create(context: Context, mediaType: PlaybackMediaType): Player =
            FakePlayer().also { players += it }

        override fun publishQueue(context: Context, player: Player): TvQueueSession {
            onPublish()
            val session = tvMediaSession(context, player).build()
            sessions += session
            return object : TvQueueSession {
                override fun swap(player: Player) {
                    session.player = player
                }

                override fun close() = session.release()
            }
        }
    }

    private fun queueRepository(first: PlaybackTarget, second: PlaybackTarget) = object : PlaybackRepository {
        override suspend fun resolve(target: PlaybackTarget, convertedOnly: Boolean) =
            PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source(fileId = target.fileId.value), false))

        override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended

        override suspend fun findNeighbours(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackNeighbours> {
            val next = if (target.fileId == first.fileId) second else null
            val previous = if (target.fileId == second.fileId) first else null
            return PlaybackRepositoryResult.Success(PlaybackNeighbours(previous, next, "Mixtapes"))
        }
    }

    private fun stoppableOwner() = object : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
