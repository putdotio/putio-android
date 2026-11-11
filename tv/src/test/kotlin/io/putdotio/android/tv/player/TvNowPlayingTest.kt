package io.putdotio.android.tv.player

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.tv.pressCenter
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.errors.PutioConfigurationException
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
class TvNowPlayingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val players = mutableListOf<FakePlayer>()
    private val sessions = mutableListOf<MediaSession>()
    private var exits = 0
    private val folderRead = CompletableDeferred(Unit)
    private val repository = FolderRepository(folderRead)

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

        compose.back()
        assertEquals(1, exits)
    }

    @Test
    fun aFinishedTrackWithoutAFolderLookupLeaves() {
        showFolder(start = SECOND, findUpNext = false)

        compose.runOnIdle { players.last().end() }
        compose.waitForIdle()

        assertEquals(1, exits)
    }

    @Test
    fun aTrackOpenedDirectlyResumesButOneSkippedToStartsOver() {
        val controller = showFolder(start = SECOND, folder = FolderRepository(folderRead, savedSeconds = 300.0))
        compose.runOnIdle { assertEquals(300_000L, players.last().startPositionMillis) }

        player().performKeyInput { pressKey(Key.MediaNext) }
        assertPlaying(controller, THIRD)

        compose.runOnIdle { assertEquals(0L, players.last().startPositionMillis) }
    }

    @Test
    fun nextIsNotFocusableOnTheFoldersLastTrack() {
        showFolder(start = THIRD)

        player().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Pause").assertIsSelected()
        compose.onNodeWithContentDescription("Next file").assertIsNotSelected()
    }

    @Test
    fun aRejectedFolderLookupReachesTheCurrentSessionHandler() {
        val pending = CompletableDeferred<Unit>()
        val rejections = mutableListOf<String>()
        var handler by mutableStateOf("first")
        val folder = FolderRepository(pending, rejected = true)
        val controller = PlaybackController(
            SECOND,
            folder,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        compose.setTvContent {
            val current = handler
            TvPlaybackRoute(
                controller = controller,
                onExit = { exits += 1 },
                onSessionRejected = { rejections += current },
                playerFactory = playerFactory,
                findUpNext = folder::findNeighbours,
            )
        }
        compose.waitForIdle()

        compose.runOnIdle { handler = "second" }
        compose.waitForIdle()
        pending.complete(Unit)
        compose.waitForIdle()

        assertEquals(listOf("second"), rejections)
    }

    @Test
    fun theSystemKeepsTheSkipControlsWhenThePlayersCommandsChange() {
        showFolder(start = SECOND)
        val session = connect(sessions.last())

        compose.runOnIdle { players.last().allow(Player.COMMAND_SET_SPEED_AND_PITCH) }
        idleSession()

        assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        session.release()
    }

    @Test
    fun aTrackThatEndsBeforeItsFolderIsReadWaitsForTheQueue() {
        val pending = CompletableDeferred<Unit>()
        val controller = showFolder(start = SECOND, folder = FolderRepository(pending))

        compose.runOnIdle { players.last().end() }
        compose.waitForIdle()
        assertEquals(0, exits)

        pending.complete(Unit)
        assertPlaying(controller, THIRD)
        assertEquals(0, exits)
    }

    @Test
    fun backLeavesATrackThatEndedBeforeItsFolderWasRead() {
        val pending = CompletableDeferred<Unit>()
        val controller = showFolder(start = SECOND, folder = FolderRepository(pending))
        compose.runOnIdle { players.last().end() }
        compose.waitForIdle()

        compose.back()
        assertEquals(1, exits)

        pending.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(SECOND.fileId, controller.state.value.target.fileId)
            assertEquals(1, exits)
        }
    }

    @Test
    fun restartingATrackThatEndedBeforeItsFolderWasReadKeepsIt() {
        val pending = CompletableDeferred<Unit>()
        val controller = showFolder(start = SECOND, folder = FolderRepository(pending))
        compose.runOnIdle { players.last().end() }
        compose.waitForIdle()

        player().performKeyInput { pressKey(Key.MediaPrevious) }
        compose.waitForIdle()
        pending.complete(Unit)
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(SECOND.fileId, controller.state.value.target.fileId)
            assertEquals(1, players.size)
            assertEquals(0, exits)
        }
    }

    @Test
    fun aTrackThatEndedBeforeItsFolderWasReadStaysPausedOnceTheAppStops() {
        val pending = CompletableDeferred<Unit>()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        val controller = showFolder(start = SECOND, folder = FolderRepository(pending), lifecycleOwner = owner)
        compose.runOnIdle { players.last().end() }
        compose.waitForIdle()

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        pending.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(SECOND.fileId, controller.state.value.target.fileId)
            assertEquals(1, players.size)
            assertEquals(0, exits)
            assertFalse(players.last().playWhenReady)
        }
    }

    @Test
    fun theSystemCannotSkipTracksWhileTheAppIsStopped() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        val controller = showFolder(start = SECOND, lifecycleOwner = owner)
        val session = connect(sessions.last())

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        session.seekToNext()
        idleSession()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(SECOND.fileId, controller.state.value.target.fileId)
            assertEquals(1, players.size)
            assertFalse(players.last().playWhenReady)
        }
        session.release()
    }

    @Test
    fun backSendsAPlayingTrackToTheBackgroundWithoutStoppingIt() {
        var backgrounds = 0
        showFolder(start = SECOND, onBackground = { backgrounds += 1 })

        compose.back()

        compose.runOnIdle {
            assertEquals(1, backgrounds)
            assertEquals(0, exits)
            assertFalse(players.single().released)
            assertTrue(players.single().playWhenReady)
        }
    }

    @Test
    fun backgroundAudioKeepsPlayingWhileTheAppIsStoppedAndTheSystemStillControlsIt() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        showFolder(start = SECOND, lifecycleOwner = owner, onBackground = {})
        val session = connect(sessions.last())

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(players.single().playWhenReady) }

        session.pause()
        idleSession()
        compose.runOnIdle { assertFalse(players.single().playWhenReady) }
        session.play()
        idleSession()
        compose.runOnIdle { assertTrue(players.single().playWhenReady) }
        session.release()
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

    private fun showFolder(
        start: PlaybackTarget,
        folder: FolderRepository = repository,
        lifecycleOwner: LifecycleOwner? = null,
        findUpNext: Boolean = true,
        onBackground: (() -> Unit)? = null,
    ): PlaybackController {
        val controller = PlaybackController(
            start,
            folder,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        compose.setTvContent {
            CompositionLocalProvider(LocalLifecycleOwner provides (lifecycleOwner ?: LocalLifecycleOwner.current)) {
                TvPlaybackRoute(
                    controller = controller,
                    onExit = { exits += 1 },
                    onSessionRejected = {},
                    playerFactory = playerFactory,
                    findUpNext = if (findUpNext) folder::findNeighbours else null,
                    onBackground = onBackground,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TV_NOW_PLAYING_TAG).assertIsDisplayed()
        return controller
    }

    private val playerFactory = object : TvPlayerFactory {
        override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Player =
            FakePlayer().also { players += it }

        override fun publish(context: android.content.Context, player: Player): Closeable {
            val session = tvMediaSession(context, player).build()
            sessions += session
            return Closeable { session.release() }
        }
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

    private class FolderRepository(
        private val read: CompletableDeferred<Unit>,
        private val savedSeconds: Double = 0.0,
        private val rejected: Boolean = false,
    ) : PlaybackRepository {
        private val folder = listOf(FIRST, SECOND, THIRD)

        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(
                PlaybackResolution.Ready(
                    source(startFromSeconds = savedSeconds, fileId = target.fileId.value),
                    useStartFrom = savedSeconds > 0.0,
                ),
            )

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended

        override suspend fun findNeighbours(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackNeighbours> {
            read.await()
            if (rejected) {
                return PlaybackRepositoryResult.Failure(
                    PlaybackFailure.Putio(PutioFailure.AuthenticationRequired(PutioConfigurationException("401"))),
                )
            }
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
