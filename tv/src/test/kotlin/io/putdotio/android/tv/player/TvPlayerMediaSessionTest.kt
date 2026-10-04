package io.putdotio.android.tv.player

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.playback.PlaybackMediaType
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
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                if (showing) {
                    TvPlayerScreen(
                        state = readyState(),
                        onBack = {},
                        onRetry = {},
                        onResume = {},
                        onRestart = {},
                        onPlayerFailure = { _, _ -> },
                        playerFactory = factory,
                    )
                }
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
    fun aSystemPauseDuringAScrubKeepsItsTargetAndBackNoLongerResumes() {
        val player = FakePlayer()
        val sessions = mutableListOf<MediaSession>()
        compose.showReady(player, resumePositionMillis = 42_000L, playerFactory = sessionFactory(player, sessions))
        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.settle()
        compose.runOnIdle { assertFalse(player.playWhenReady) }

        // The scrub already paused the player, so this pause changes nothing it reports.
        val controller = connect(sessions.single())
        controller.pause()
        idleSession()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("00:27")

        compose.back()
        compose.runOnIdle {
            assertEquals("Dismissing seek mode never seeks", 42_000L, player.currentPosition)
            assertFalse("The system's pause holds", player.playWhenReady)
        }
        controller.release()
    }

    @Test
    fun aSystemSeekDuringAScrubReplacesItsTarget() {
        val player = FakePlayer()
        val sessions = mutableListOf<MediaSession>()
        compose.showReady(player, resumePositionMillis = 42_000L, playerFactory = sessionFactory(player, sessions))
        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.settle()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("00:27")

        val controller = connect(sessions.single())
        controller.seekTo(90_000L)
        idleSession()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("01:30")

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.settle()
        compose.runOnIdle { assertEquals("Center cannot undo the system's seek", 90_000L, player.currentPosition) }
        controller.release()
    }

    @Test
    fun theSystemCannotResumePlaybackWhileTheScreenIsStopped() {
        val player = FakePlayer()
        val sessions = mutableListOf<MediaSession>()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        compose.showReady(player, playerFactory = sessionFactory(player, sessions), lifecycleOwner = owner)
        val controller = connect(sessions.single())

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.settle()
        compose.runOnIdle { assertFalse(player.playWhenReady) }
        controller.play()
        idleSession()
        compose.runOnIdle { assertFalse("Nothing plays behind another app", player.playWhenReady) }

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.settle()
        controller.play()
        idleSession()
        compose.runOnIdle { assertTrue(player.playWhenReady) }
        controller.release()
    }

    @Test
    fun aPauseFromTheSystemControlsShowsThePausedControls() {
        val player = FakePlayer()
        compose.showReady(player)
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + 100L)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()

        // The media session drives the player directly, as for a Now Playing pause.
        compose.runOnIdle { player.pause() }
        compose.settle()
        compose.onNodeWithContentDescription("Paused").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS * 2)
        compose.onNodeWithContentDescription("Paused").assertIsDisplayed()

        compose.runOnIdle { player.play() }
        compose.settle()
        compose.onNodeWithContentDescription("Playing").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + 100L)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
    }

    /** Publishes [player] through the app's real session, as the default factory does. */
    private fun sessionFactory(player: FakePlayer, sessions: MutableList<MediaSession>) = object : TvPlayerFactory {
        override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Player = player

        override fun publish(context: android.content.Context, published: Player): java.io.Closeable {
            val session = tvMediaSession(context, published).build()
            sessions += session
            return java.io.Closeable { session.release() }
        }
    }

    /** A system controller, as Now Playing or a remote's media keys reach the session. */
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
