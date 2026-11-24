package io.putdotio.android.tv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.media.session.PlaybackState as PlatformPlaybackState
import android.os.Looper
import io.putdotio.android.tv.setTvContent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlayerMediaSessionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theScreenPublishesItsPlayerAsAMediaSessionUntilPlaybackEnds() {
        val player = FakePlayer()
        val events = mutableListOf<String>()
        var showing by mutableStateOf(true)
        val factory = object : TvPlayerFactory {
            override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Player = player

            override fun publish(context: android.content.Context, published: Player): java.io.Closeable {
                events += "published ${published.currentMediaItem?.mediaId}"
                return java.io.Closeable { events += "unpublished released=${player.released}" }
            }
        }
        compose.setTvContent {
            if (showing) {
                TvPlayerScreen(
                    state = readyState(),
                    onBack = {},
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = factory,
                )
            }
        }
        compose.runOnIdle { assertEquals(listOf("published 9"), events) }

        compose.runOnIdle { showing = false }
        compose.runOnIdle {
            assertEquals(listOf("published 9", "unpublished released=false"), events)
            assertTrue(player.released)
        }
    }

    @Test
    fun theSessionLetsControllersPlayPauseAndSeekButNotSwapTheFile() {
        val player = FakePlayer()
        player.setMediaItem(MediaItem.Builder().setMediaId("9").setUri(SOURCE_URL).build())
        player.prepare()
        player.play()
        val context = compose.activity
        val session = tvMediaSession(context, player).build()
        try {
            val pending = MediaController.Builder(context, session.token).buildAsync()
            shadowOf(Looper.getMainLooper()).idle()
            val controller = pending.get()
            assertTrue(controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            assertFalse(controller.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
            assertFalse(controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))

            controller.setMediaItem(
                MediaItem.Builder().setMediaId("10").setUri("https://example.com/other.mp4").build(),
            )
            controller.pause()
            controller.seekTo(5_000L)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("9"), player.mediaItems.map { it.mediaId })
            assertFalse(player.playWhenReady)
            assertEquals(5_000L, player.currentPosition)
            controller.release()
        } finally {
            session.release()
        }
    }

    @Test
    fun theBackgroundQueueKeepsOneSystemSessionAcrossTracks() {
        val first = FakePlayer().apply { setMediaItem(item("9")) }
        val second = FakePlayer().apply { setMediaItem(item("10")) }
        val queue = DefaultTvPlayerFactory.publishQueue(compose.activity, first)
        try {
            val hosted = checkNotNull(TvBackgroundSessions.hosted)
            val controller = connect(hosted)

            queue.swap(second)
            idleSession()

            assertTrue(TvBackgroundSessions.hosted === hosted)
            assertTrue(controller.isConnected)
            assertEquals("10", controller.currentMediaItem?.mediaId)
            controller.release()
        } finally {
            queue.close()
        }
    }

    @Test
    fun systemControlsSkipAsFarAsTheRemoteBothWays() {
        val player = DefaultTvPlayerFactory.create(compose.activity, PlaybackMediaType.VIDEO)
        val session = tvMediaSession(compose.activity, player).build()
        try {
            val controller = connect(session)
            assertEquals(TV_SKIP_MILLIS, controller.seekBackIncrement)
            assertEquals(TV_SKIP_MILLIS, controller.seekForwardIncrement)
            controller.release()
        } finally {
            session.release()
            player.release()
        }
    }

    @Test
    fun aSystemPauseAfterASkipHoldsAndBackOnlyHidesTheControls() {
        val player = FakePlayer()
        val sessions = mutableListOf<MediaSession>()
        compose.showReady(player, resumePositionMillis = 42_000L, playerFactory = sessionFactory(player, sessions))
        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionLeft)
        }
        compose.settle()
        compose.runOnIdle { assertTrue("A skip never pauses", player.playWhenReady) }

        val controller = connect(sessions.single())
        controller.pause()
        idleSession()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("0:32")

        compose.back()
        compose.runOnIdle {
            assertEquals(32_000L, player.currentPosition)
            assertFalse("The system's pause holds", player.playWhenReady)
        }
        controller.release()
    }

    @Test
    fun aSystemSeekAfterASkipStands() {
        val player = FakePlayer()
        val sessions = mutableListOf<MediaSession>()
        compose.showReady(player, resumePositionMillis = 42_000L, playerFactory = sessionFactory(player, sessions))
        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionLeft)
        }
        compose.settle()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("0:32")

        val controller = connect(sessions.single())
        controller.seekTo(90_000L)
        idleSession()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("1:30")

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.settle()
        compose.runOnIdle {
            assertEquals("Center cannot undo the system's seek", 90_000L, player.currentPosition)
            assertFalse("Center on the seek bar toggles play", player.playWhenReady)
        }
        controller.release()
    }

    @Test
    fun theSystemCannotResumePlaybackWhileTheScreenIsStopped() {
        val players = mutableListOf<FakePlayer>()
        val sessions = mutableListOf<MediaSession>()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        val factory = object : TvPlayerFactory {
            override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Player =
                FakePlayer().also { players += it }

            override fun publish(context: android.content.Context, published: Player): java.io.Closeable {
                val session = tvMediaSession(context, published).build()
                sessions += session
                return java.io.Closeable { session.release() }
            }
        }
        compose.showReady(FakePlayer(), playerFactory = factory, lifecycleOwner = owner)
        val controller = connect(sessions.single())

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.settle()
        compose.runOnIdle { assertTrue(players.single().released) }
        controller.play()
        idleSession()
        compose.runOnIdle { assertFalse("Nothing plays behind another app", players.single().playWhenReady) }

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.settle()
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertEquals(2, sessions.size)
        }
        val resumedController = connect(sessions.last())
        resumedController.play()
        idleSession()
        compose.runOnIdle { assertTrue(players.last().playWhenReady) }
        controller.release()
        resumedController.release()
    }

    @Test
    fun aPauseFromTheSystemControlsShowsThePausedControls() {
        val player = FakePlayer()
        compose.showReady(player)
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + 100L)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()

        compose.runOnIdle { player.pause() }
        compose.settle()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS * 2)
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()

        compose.runOnIdle { player.play() }
        compose.settle()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + 100L)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
    }

    @Test
    fun audioSessionUpdatesPlatformPlaybackStateAcrossBufferingPlaySeekAndPause() {
        val player = FakePlayer()
        player.setMediaItem(item("9"))
        player.playWhenReady = true
        player.buffer()
        idleSession()
        val session = tvMediaSession(
            compose.activity,
            tvSessionPlayer(
                player = player,
                onSkipRequested = {},
                onPlayingRequested = { player.playWhenReady = it },
            ),
        ).build()
        val controller = connect(session)
        try {
            idleSession()
            val buffering = platformPlaybackState(session)!!
            assertEquals(PlatformPlaybackState.STATE_BUFFERING, buffering.state)
            assertEquals(0L, buffering.position)

            player.ready()
            idleSession()
            assertEquals(Player.STATE_READY, controller.playbackState)
            assertTrue(controller.playWhenReady)
            assertEquals(PlatformPlaybackState.STATE_PLAYING, platformPlaybackState(session)?.state)

            player.seekTo(30_000L)
            idleSession()
            val playingSeeked = platformPlaybackState(session)!!
            assertEquals(30_000L, controller.currentPosition)
            assertEquals(PlatformPlaybackState.STATE_PLAYING, playingSeeked.state)
            assertEquals(30_000L, playingSeeked.position)

            player.playWhenReady = false
            idleSession()
            val paused = platformPlaybackState(session)!!
            assertFalse(controller.playWhenReady)
            assertEquals(PlatformPlaybackState.STATE_PAUSED, paused.state)
            assertEquals(0f, paused.playbackSpeed)
            assertEquals(30_000L, paused.position)

            player.allow(Player.COMMAND_SET_SPEED_AND_PITCH)
            idleSession()
            assertTrue(controller.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH))
            assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            val actions = platformPlaybackState(session)!!.actions
            assertTrue((actions and PlatformPlaybackState.ACTION_SKIP_TO_NEXT) != 0L)
            assertTrue((actions and PlatformPlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L)
        } finally {
            controller.release()
            session.release()
        }
    }

    @Test
    fun queueSessionGrantsSkipCommandsToExistingControllerOnceTrackTakesOver() {
        val loading = tvSessionPlayer(
            player = LoadingAudioPlayer(PlaybackTarget(FilesItemId(9), "01 Track.mp3", PlaybackMediaType.AUDIO)),
            onSkipRequested = null,
            onPlayingRequested = {},
        )
        val real = FakePlayer().apply {
            setMediaItem(item("9"))
            ready()
        }
        val queue = DefaultTvPlayerFactory.publishQueue(compose.activity, loading)
        val hosted = checkNotNull(TvBackgroundSessions.hosted)
        val controller = connect(hosted)
        try {
            idleSession()
            assertFalse(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
            assertFalse(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))

            queue.swap(tvSessionPlayer(real, onSkipRequested = {}, onPlayingRequested = {}))
            idleSession()

            assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
            assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
            val actions = platformPlaybackState(hosted)!!.actions
            assertTrue((actions and PlatformPlaybackState.ACTION_SKIP_TO_NEXT) != 0L)
            assertTrue((actions and PlatformPlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L)
        } finally {
            controller.release()
            queue.close()
        }
    }

    private fun platformPlaybackState(session: MediaSession): PlatformPlaybackState? {
        val impl = MediaSession::class.java.getDeclaredField("impl").apply { isAccessible = true }.get(session)
        val stub = impl.javaClass.getDeclaredField("sessionLegacyStub").apply { isAccessible = true }.get(impl)
        val compat = stub.javaClass.getMethod("getSessionCompat").apply { isAccessible = true }.invoke(stub)
        val compatImpl = compat.javaClass.getDeclaredField("impl").apply { isAccessible = true }.get(compat)
        val stateCompat = compatImpl.javaClass.getMethod("getPlaybackState")
            .apply { isAccessible = true }
            .invoke(compatImpl)
            ?: return null
        return stateCompat.javaClass.getMethod("getPlaybackState").apply { isAccessible = true }
            .invoke(stateCompat) as? PlatformPlaybackState
    }

    private fun sessionFactory(player: FakePlayer, sessions: MutableList<MediaSession>) = object : TvPlayerFactory {
        override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Player = player

        override fun publish(context: android.content.Context, published: Player): java.io.Closeable {
            val session = tvMediaSession(context, published).build()
            sessions += session
            return java.io.Closeable { session.release() }
        }
    }

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri(SOURCE_URL).build()

    private fun connect(session: MediaSession): MediaController {
        val pending = MediaController.Builder(compose.activity, session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        return pending.get()
    }

    private fun idleSession() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.settle()
    }
}
