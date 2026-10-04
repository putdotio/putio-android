package io.putdotio.android

import android.app.Application
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Looper
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerScreen
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackRequestId
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.fittedVideoBounds
import io.putdotio.android.playback.pictureInPictureAspectRatio
import io.putdotio.android.playback.playbackState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-port")
@UnstableApi
class MobilePictureInPictureTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aPlayingVideoArmsTheWindowInItsShapeAndAPausedOneDisarmsIt() {
        val host = launchHost()
        val activity = host.get()
        var showPlayer by mutableStateOf(true)
        val player = RecordingPlayer()
        val factory = MobilePlayerFactory { _, _ -> player }
        activity.setContent {
            PutioTheme {
                if (showPlayer) PlayerScreen(videoState(), factory)
            }
        }
        reportPlaying(player)
        compose.runOnIdle { player.updateVideoSize(VideoSize(1_920, 1_080)) }
        compose.runOnIdle {
            val armed = activity.params.last()
            assertTrue(armed.isAutoEnterEnabled)
            assertEquals(Rational(1_920, 1_080), armed.aspectRatio)
            val hint = checkNotNull(armed.sourceRectHint)
            // A landscape video letterboxes inside the portrait window, centred.
            assertEquals(activity.window.decorView.width, hint.width())
            assertEquals(activity.window.decorView.width * 1_080.0 / 1_920, hint.height().toDouble(), 1.0)
            assertEquals(activity.window.decorView.height / 2.0, hint.centerY().toDouble(), 1.0)
            assertEquals("Pause", armed.actions.single().title)
            player.pause()
        }
        compose.runOnIdle {
            val paused = activity.params.last()
            assertFalse(paused.isAutoEnterEnabled)
            assertEquals("Play", paused.actions.single().title)
            player.play()
        }
        compose.runOnIdle {
            assertTrue(activity.params.last().isAutoEnterEnabled)
            showPlayer = false
        }
        compose.runOnIdle { assertFalse(activity.params.last().isAutoEnterEnabled) }
        host.close()
    }

    @Test
    fun audioNeverArmsTheWindow() {
        val host = launchHost()
        val activity = host.get()
        val session = RecordingPlayer()
        val factory = object : MobilePlayerFactory {
            override fun create(context: Context, mediaType: PlaybackMediaType): Media3Player =
                error("audio attaches to the session")

            override fun connectAudio(
                context: Context,
                onResult: (Result<Media3Player>) -> Unit,
            ): java.io.Closeable {
                onResult(Result.success(session))
                return java.io.Closeable {}
            }
        }
        activity.setContent { PutioTheme { PlayerScreen(audioState(), factory) } }
        compose.runOnIdle {
            assertTrue(session.playWhenReady)
            assertEquals(Media3Player.STATE_READY, session.playbackState)
            assertTrue(activity.params.none { it.isAutoEnterEnabled })
        }
        host.close()
    }

    @Test
    fun theWindowKeepsPlayingItsControlsDriveThePlayerAndExpandingKeepsTheirChoice() {
        val host = launchHost()
        val activity = host.get()
        val player = RecordingPlayer()
        activity.setContent { PutioTheme { PlayerScreen(videoState(), MobilePlayerFactory { _, _ -> player }) } }
        reportPlaying(player)

        compose.runOnIdle {
            activity.pictureInPicture(true)
            host.pause()
            assertTrue(player.playWhenReady)
        }
        compose.runOnIdle { activity.sendPictureInPictureAction("Pause") }
        compose.runOnIdle {
            assertFalse(player.playWhenReady)
            activity.sendPictureInPictureAction("Play")
        }
        compose.runOnIdle {
            assertTrue(player.playWhenReady)
            activity.sendPictureInPictureAction("Pause")
        }
        compose.runOnIdle {
            activity.pictureInPicture(false)
            host.resume()
        }
        compose.runOnIdle {
            assertFalse(player.released)
            assertFalse(player.playWhenReady)
        }
        host.close()
    }

    @Test
    fun aPauseThatBeatsTheModeChangeIsResumedByTheWindow() {
        val host = launchHost()
        val activity = host.get()
        val player = RecordingPlayer()
        activity.setContent { PutioTheme { PlayerScreen(videoState(), MobilePlayerFactory { _, _ -> player }) } }
        compose.runOnIdle {
            host.pause()
            assertFalse(player.playWhenReady)
            activity.pictureInPicture(true)
        }
        compose.runOnIdle { assertTrue(player.playWhenReady) }
        host.close()
    }

    @Test
    fun theWindowShowsOnlyTheVideo() {
        val host = launchHost()
        val activity = host.get()
        val player = RecordingPlayer()
        activity.setContent { PutioTheme { PlayerScreen(videoState(), MobilePlayerFactory { _, _ -> player }) } }
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()

        compose.runOnIdle { activity.pictureInPicture(true) }
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()

        compose.runOnIdle { activity.pictureInPicture(false) }
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        host.close()
    }

    @Test
    fun autoplayNextKeepsPlayingInTheWindow() {
        val host = launchHost()
        val activity = host.get()
        val players = mutableListOf<RecordingPlayer>()
        var state by mutableStateOf(videoState())
        val factory = MobilePlayerFactory { _, _ -> RecordingPlayer().also(players::add) }
        activity.setContent { PutioTheme { PlayerScreen(state, factory) } }
        compose.runOnIdle {
            activity.pictureInPicture(true)
            host.pause()
            state = videoState(content = PlaybackContent.FindingNext(PlaybackRequestId(3L)))
        }
        compose.runOnIdle {
            assertTrue(players.single().released)
            state = videoState(target = NextTarget)
        }
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertEquals(NextTarget.fileId.value.toString(), players.last().currentMediaItem?.mediaId)
            assertTrue(players.last().playWhenReady)
        }
        host.close()
    }

    @Test
    fun closingTheWindowStopsTheVideoAndTheNextVisitFindsItPaused() {
        val host = launchHost()
        val activity = host.get()
        val players = mutableListOf<RecordingPlayer>()
        val factory = MobilePlayerFactory { _, _ -> RecordingPlayer().also(players::add) }
        activity.setContent { PutioTheme { PlayerScreen(videoState(), factory) } }
        compose.runOnIdle {
            activity.pictureInPicture(true)
            host.pause()
            players.single().movePositionTo(54_321L)
            host.stop()
            assertTrue(players.single().released)
            activity.pictureInPicture(false)
        }
        compose.runOnIdle {
            host.start()
            host.resume()
        }
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertEquals(54_321L, players.last().currentPosition)
            assertFalse(players.last().playWhenReady)
        }
        host.close()
    }

    @Test
    fun theWindowStaysWithinThePlatformShapeLimits() {
        assertEquals(Rational(238, 100), pictureInPictureAspectRatio(VideoSize(3_840, 1_080)))
        assertEquals(Rational(100, 238), pictureInPictureAspectRatio(VideoSize(1_080, 3_840)))
        assertEquals(Rational(1_440, 1_080), pictureInPictureAspectRatio(VideoSize(720, 1_080, 2f)))
        assertEquals(null, pictureInPictureAspectRatio(VideoSize.UNKNOWN))
        assertEquals(
            android.graphics.Rect(0, 200, 1_000, 400),
            Rect(0f, 0f, 1_000f, 600f).fittedVideoBounds(Rational(5, 1)),
        )
    }

    @Test
    fun windowResizesKeepTheActivityAndItsPlayer() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val before = controller.get()
        val pictureInPicture = Configuration(before.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            screenWidthDp = 240
            screenHeightDp = 135
            smallestScreenWidthDp = 135
            screenLayout = Configuration.SCREENLAYOUT_SIZE_SMALL or Configuration.SCREENLAYOUT_LONG_NO
        }
        controller.configurationChange(pictureInPicture)
        assertSame(before, controller.get())
        controller.close()
    }

    @androidx.compose.runtime.Composable
    private fun PlayerScreen(state: PlaybackState, factory: MobilePlayerFactory) {
        MobilePlayerScreen(
            state = state,
            onRetry = {},
            onPlayerFailure = { _, _ -> },
            onBack = {},
            playerFactory = factory,
        )
    }

    // The fake prepares and plays synchronously, before the screen's listener attaches; a real
    // player reports READY later. Pausing and playing again reports the state a real one would.
    private fun reportPlaying(player: RecordingPlayer) {
        compose.runOnIdle { player.pause() }
        compose.runOnIdle { player.play() }
        compose.runOnIdle { assertTrue(player.playWhenReady) }
    }

    private fun launchHost(): ActivityController<PictureInPictureHostActivity> {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        return Robolectric.buildActivity(PictureInPictureHostActivity::class.java).setup()
    }

    private fun ComponentActivity.pictureInPicture(active: Boolean) =
        onPictureInPictureModeChanged(active, Configuration(resources.configuration))

    private fun PictureInPictureHostActivity.sendPictureInPictureAction(title: String) {
        params.last().actions.single { it.title == title }.actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
    }
}

/** Records what the screen asks of the platform; Robolectric has no window manager to enter it. */
class PictureInPictureHostActivity : ComponentActivity() {
    val params = mutableListOf<PictureInPictureParams>()

    override fun setPictureInPictureParams(params: PictureInPictureParams) {
        this.params += params
    }
}

private val VideoTarget = PlaybackTarget(FilesItemId(42L), "episode.mkv")
private val NextTarget = PlaybackTarget(FilesItemId(44L), "episode-2.mkv")
private val AudioTarget = PlaybackTarget(FilesItemId(43L), "song.mp3", PlaybackMediaType.AUDIO)

private fun videoState(
    target: PlaybackTarget = VideoTarget,
    content: PlaybackContent = PlaybackContent.Ready(source(target, PlaybackSourceKind.MP4)),
): PlaybackState = playbackState(target = target, content = content, nextRequestValue = 4L)

private fun audioState(): PlaybackState =
    playbackState(
        target = AudioTarget,
        content = PlaybackContent.Ready(source(AudioTarget, PlaybackSourceKind.ORIGINAL)),
        nextRequestValue = 2L,
    )

private fun source(target: PlaybackTarget, kind: PlaybackSourceKind): PlaybackSource =
    PlaybackSource(
        fileId = target.fileId.value,
        kind = kind,
        // Credential URLs can only be minted by the SDK resolver in production.
        url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance("https://example.com/${target.name}"),
        startFromSeconds = 0.0,
        subtitles = PlaybackSubtitles.None,
    )
