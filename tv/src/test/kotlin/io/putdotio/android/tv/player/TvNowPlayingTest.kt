package io.putdotio.android.tv.player

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.tv.pressCenter
import io.putdotio.android.tv.setTvContent
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
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
class TvNowPlayingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val players = mutableListOf<FakePlayer>()
    private val sessions = mutableListOf<MediaSession>()
    private var exits = 0

    @Test
    fun nextAndPreviousMoveThroughTheFolderAndPreviousRestartsAPlayingTrack() {
        val controller = showFolder(start = SECOND)
        compose.onNodeWithText("Mixtapes").assertIsDisplayed()

        player().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
            pressCenter()
        }
        compose.waitForIdle()
        assertPlaying(controller, THIRD)

        player().performKeyInput { pressKey(Key.MediaPrevious) }
        compose.waitForIdle()
        assertPlaying(controller, SECOND)
        compose.onNodeWithContentDescription("Next file").assertIsSelected()

        compose.runOnIdle { players.last().advanceTo(RESTART_AT_MILLIS) }
        player().performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionLeft)
            pressCenter()
        }
        compose.waitForIdle()
        assertPlaying(controller, SECOND)
        compose.runOnIdle { assertTrue("Restarted", players.last().currentPosition < RESTART_AT_MILLIS / 10) }

        player().performKeyInput { pressKey(Key.MediaNext) }
        compose.waitForIdle()
        assertPlaying(controller, THIRD)
    }

    @Test
    fun aFinishedTrackPlaysTheNextAndTheFoldersLastLeaves() {
        val controller = showFolder(start = SECOND)

        compose.runOnIdle { players.last().end() }
        compose.waitForIdle()
        assertPlaying(controller, THIRD)
        assertEquals(0, exits)

        compose.runOnIdle { players.last().end() }
        compose.waitForIdle()
        assertEquals(1, exits)
    }

    @Test
    fun theSystemsSkipControlsMoveThroughTheFolder() {
        val controller = showFolder(start = SECOND)
        val session = connect(sessions.last())
        assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))

        session.seekToNext()
        idleSession()
        assertPlaying(controller, THIRD)

        val next = connect(sessions.last())
        next.seekToPrevious()
        idleSession()
        assertPlaying(controller, SECOND)
        session.release()
        next.release()
    }

    private fun showFolder(start: PlaybackTarget): PlaybackController {
        val controller = PlaybackController(
            start,
            FolderRepository,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        compose.setTvContent {
            TvPlaybackRoute(
                controller = controller,
                onExit = { exits += 1 },
                onSessionRejected = {},
                playerFactory = object : TvPlayerFactory {
                    override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Player =
                        FakePlayer().also { players += it }

                    override fun publish(context: android.content.Context, player: Player): Closeable {
                        val session = tvMediaSession(context, player).build()
                        sessions += session
                        return Closeable { session.release() }
                    }
                },
                findUpNext = FolderRepository::findNeighbours,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TV_NOW_PLAYING_TAG).assertIsDisplayed()
        return controller
    }

    private fun player() = compose.onNodeWithTag(TV_PLAYER_TAG)

    private fun assertPlaying(controller: PlaybackController, target: PlaybackTarget) {
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(target.fileId, controller.state.value.target.fileId)
            assertEquals(target.fileId.value.toString(), players.last().currentMediaItem?.mediaId)
        }
        compose.onNodeWithText(target.name).assertIsDisplayed()
    }

    private fun connect(session: MediaSession): MediaController {
        val pending = MediaController.Builder(compose.activity, session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        return pending.get()
    }

    private fun idleSession() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private object FolderRepository : PlaybackRepository {
        private val folder = listOf(FIRST, SECOND, THIRD)

        override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source(fileId = target.fileId.value)))

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended

        override suspend fun findNeighbours(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackNeighbours> {
            val at = folder.indexOfFirst { it.fileId == target.fileId }
            return PlaybackRepositoryResult.Success(
                PlaybackNeighbours(folder.getOrNull(at - 1), folder.getOrNull(at + 1), "Mixtapes"),
            )
        }
    }

    private companion object {
        const val RESTART_AT_MILLIS = 30_000L
        val FIRST = PlaybackTarget(FilesItemId(10), "01 Opening.mp3", PlaybackMediaType.AUDIO)
        val SECOND = PlaybackTarget(FilesItemId(11), "02 Middle.mp3", PlaybackMediaType.AUDIO)
        val THIRD = PlaybackTarget(FilesItemId(12), "03 Closing.mp3", PlaybackMediaType.AUDIO)
    }
}
