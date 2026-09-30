package io.putdotio.android.tv.player

import android.os.Looper
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The TV player on a fake Media3 player; the emulator lane plays real media through ExoPlayer. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlayerScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun readySourcePlaysAndCenterTogglesPauseWithTheControlsShown() {
        val player = FakePlayer()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlayerScreen(
                    state = readyState(resumePositionMillis = 42_000L),
                    onBack = {},
                    onRetry = {},
                    onResume = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> player },
                )
            }
        }
        settle()
        compose.runOnIdle {
            val item = player.mediaItems.single()
            assertEquals("9", item.mediaId)
            assertEquals(SOURCE_URL, item.localConfiguration?.uri.toString())
            assertEquals(42_000L, player.startPositionMillis)
            assertTrue(player.prepared)
            assertTrue(player.playWhenReady)
        }
        compose.onNodeWithText("Sintel.mp4").assertIsDisplayed()
        compose.onNodeWithContentDescription("Playing").assertIsDisplayed()

        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        settle()
        compose.runOnIdle { assertFalse(player.playWhenReady) }
        // Paused controls stay up past the auto-hide delay.
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS * 2)
        compose.onNodeWithContentDescription("Paused").assertIsDisplayed()

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.MediaPlayPause) }
        settle()
        compose.runOnIdle { assertTrue(player.playWhenReady) }
        compose.onNodeWithContentDescription("Playing").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + 100L)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()

        // Any key reveals the controls without changing playback.
        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        settle()
        compose.onNodeWithText("Sintel.mp4").assertIsDisplayed()
        compose.runOnIdle { assertTrue(player.playWhenReady) }
    }

    @Test
    fun backHidesTheControlsThenLeavesAndThePlayerIsReleased() {
        val player = FakePlayer()
        var backs = 0
        var showing by mutableStateOf(true)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                if (showing) {
                    TvPlayerScreen(
                        state = readyState(),
                        onBack = {
                            backs += 1
                            showing = false
                        },
                        onRetry = {},
                        onResume = {},
                        onPlayerFailure = { _, _ -> },
                        playerFactory = { _, _ -> player },
                    )
                }
            }
        }
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertIsDisplayed()

        back()
        assertEquals(0, backs)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()

        back()
        assertEquals(1, backs)
        compose.onNodeWithTag(TV_PLAYER_TAG).assertDoesNotExist()
        assertTrue(player.released)
    }

    @Test
    fun leavingTheAppPausesAndARecreatedScreenContinuesFromThereStillPaused() {
        val players = mutableListOf<FakePlayer>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlayerScreen(
                    state = readyState(resumePositionMillis = 42_000L),
                    onBack = {},
                    onRetry = {},
                    onResume = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> FakePlayer().also { players += it } },
                )
            }
        }
        compose.runOnIdle { players.single().advanceTo(61_000L) }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnIdle { assertFalse(players.single().playWhenReady) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            val (first, recreated) = players
            assertTrue(first.released)
            assertEquals(61_000L, recreated.startPositionMillis)
            assertFalse(recreated.playWhenReady)
        }
        compose.onNodeWithContentDescription("Paused").assertIsDisplayed()
    }

    @Test
    fun aPlayerErrorReportsItsPositionAndTheFailureOffersRetry() {
        val player = FakePlayer()
        var reported: Pair<PlaybackFailure, Long>? = null
        var retries = 0
        var state by mutableStateOf(readyState())
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlayerScreen(
                    state = state,
                    onBack = {},
                    onRetry = { retries += 1 },
                    onResume = {},
                    onPlayerFailure = { failure, position -> reported = failure to position },
                    playerFactory = { _, _ -> player },
                )
            }
        }
        compose.runOnIdle { player.fail(positionMillis = 61_000L) }
        compose.waitForIdle()
        val (failure, position) = checkNotNull(reported)
        assertEquals(61_000L, position)

        state = state.copy(content = PlaybackContent.Failed(failure))
        compose.onNodeWithText("Couldn’t play this file").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun aSavedPositionContinuesUntilTheResumePromptExists() {
        var resumes = 0
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlayerScreen(
                    state = readyState().copy(content = PlaybackContent.AwaitingResume(source(startFromSeconds = 30.0))),
                    onBack = {},
                    onRetry = {},
                    onResume = { resumes += 1 },
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> error("No player before the choice") },
                )
            }
        }
        compose.runOnIdle { assertEquals(1, resumes) }
    }

    @Test
    fun selectingAVideoPlaysItAndBackReturnsToItsFilesRow() {
        val player = FakePlayer()
        val files = FilesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(
                        listOf(row(1, "notes.txt", PutioFileType.TEXT), row(9, "Sintel.mp4", PutioFileType.VIDEO)),
                        FilesPaging.Complete,
                    ),
                ),
            ),
            nextRequestValue = 10L,
        )
        // Owned by the session in the app, so it outlives the shell while playback shows.
        val focusMemory = mutableMapOf<Long, Long>()
        var playing by mutableStateOf<FilesItem?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackLayer(
                    playing = playing != null,
                    player = {
                        val item = checkNotNull(playing)
                        TvPlayerScreen(
                            state = readyState(name = item.name),
                            onBack = { playing = null },
                            onRetry = {},
                            onResume = {},
                            onPlayerFailure = { _, _ -> },
                            playerFactory = { _, _ -> player },
                        )
                    },
                ) {
                    TvShell(
                        account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                        onSignOut = {},
                        filesPane = { paneFocus ->
                            TvFilesScreen(
                                state = files,
                                onEvent = { true },
                                onPlayMedia = { playing = it },
                                modifier = Modifier.focusRequester(paneFocus),
                                focusMemory = focusMemory,
                            )
                        },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Play Sintel.mp4").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        compose.onNodeWithContentDescription("Play Sintel.mp4").assertDoesNotExist()
        compose.runOnIdle { assertTrue(player.playWhenReady) }

        // The first Back hides the controls; the second leaves.
        back()
        assertTrue(playing != null)
        back()

        assertNull(playing)
        assertTrue(player.released)
        compose.onNodeWithContentDescription("Play Sintel.mp4").assertIsFocused()
    }

    @Test
    fun backOnTheShellAfterPlaybackStillReachesTheShell() {
        // Playback registers its own Back; once it is gone the shell's handlers own Back again.
        // The first Back hides the controls, the second leaves playback.
        var shellBacks = 0
        var playing by mutableStateOf(true)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackLayer(
                    playing = playing,
                    player = {
                        TvPlayerScreen(
                            state = readyState(),
                            onBack = { playing = false },
                            onRetry = {},
                            onResume = {},
                            onPlayerFailure = { _, _ -> },
                            playerFactory = { _, _ -> FakePlayer() },
                        )
                    },
                ) {
                    BackHandler { shellBacks += 1 }
                }
            }
        }
        back()
        back()
        assertEquals(0, shellBacks)
        back()
        assertEquals(1, shellBacks)
    }

    @Test
    fun theShellKeepsItsSavedStateWhilePlaybackShows() {
        var playing by mutableStateOf(false)
        // A fresh shell would read the next value; a restored one keeps the first.
        var nextValue = 3
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackLayer(
                    playing = playing,
                    player = { Text("Player") },
                ) {
                    // Stands in for the shell's saved destination.
                    val saved by rememberSaveable { mutableIntStateOf(nextValue++) }
                    Text("Shell $saved", modifier = Modifier.testTag("shell"))
                }
            }
        }
        compose.onNodeWithText("Shell 3").assertIsDisplayed()
        playing = true
        compose.onNodeWithText("Player").assertIsDisplayed()
        compose.onNodeWithTag("shell").assertDoesNotExist()
        playing = false
        compose.onNodeWithText("Shell 3").assertIsDisplayed()
    }

    @Test
    fun quickRightPressesScrubPausedAndCenterPlaysFromTheTarget() {
        val player = FakePlayer()
        showReady(player, resumePositionMillis = 42_000L)

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput {
            pressKey(Key.DirectionRight)
            advanceEventTime(TV_SCRUB_ACCUMULATE_MILLIS / 2)
            pressKey(Key.DirectionRight)
        }
        settle()
        // 42 s + 15 s + 30 s: the second press came inside the window, so its step doubled.
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("01:27")
        compose.runOnIdle {
            assertFalse("Scrubbing pauses", player.playWhenReady)
            assertEquals("Nothing seeks before the commit", 42_000L, player.currentPosition)
        }
        // Seek mode keeps the controls past the auto-hide delay.
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS * 2)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertIsDisplayed()

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionCenter) }
        settle()
        compose.runOnIdle {
            assertEquals(87_000L, player.currentPosition)
            assertTrue("The commit resumes playback", player.playWhenReady)
        }
    }

    @Test
    fun pressesAfterAPauseRestartTheStepFromThePendingTarget() {
        val player = FakePlayer()
        showReady(player, resumePositionMillis = 42_000L)

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput {
            pressKey(Key.MediaFastForward)
            advanceEventTime(TV_SCRUB_ACCUMULATE_MILLIS * 2)
            pressKey(Key.MediaFastForward)
            advanceEventTime(TV_SCRUB_ACCUMULATE_MILLIS * 2)
            pressKey(Key.MediaRewind)
        }
        settle()
        // 42 + 15 + 15 - 15: each press after a gap moves one step.
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("00:57")
    }

    @Test
    fun backDismissesSeekModeThenTheControlsKeepingTimeAndFocusThenExitsOnce() {
        val player = FakePlayer()
        var backs = 0
        showReady(player, resumePositionMillis = 42_000L, onBack = { backs += 1 })

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        settle()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("00:27")
        compose.runOnIdle { assertFalse(player.playWhenReady) }

        back()
        compose.runOnIdle {
            assertEquals(0, backs)
            assertEquals("Dismissing seek mode never seeks", 42_000L, player.currentPosition)
            assertTrue("It resumes the playback the scrub paused", player.playWhenReady)
        }
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("00:42")

        back()
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        compose.runOnIdle {
            assertEquals(0, backs)
            assertTrue(player.playWhenReady)
        }

        back()
        back()
        compose.runOnIdle { assertEquals("Exit dispatches once", 1, backs) }
    }

    @Test
    fun pausedControlsDismissWithoutResumingOrLeaving() {
        val player = FakePlayer()
        var backs = 0
        showReady(player, onBack = { backs += 1 })

        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionCenter) }
        settle()
        compose.runOnIdle { assertFalse(player.playWhenReady) }

        back()
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        compose.runOnIdle {
            assertFalse("Still paused", player.playWhenReady)
            assertEquals(0, backs)
        }

        // Any key brings the paused controls back.
        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionUp) }
        settle()
        compose.onNodeWithContentDescription("Paused").assertIsDisplayed()
        compose.runOnIdle { assertFalse(player.playWhenReady) }
    }

    @Test
    fun aHeldBackKeyDismissesOneLayer() {
        val player = FakePlayer()
        var backs = 0
        showReady(player, onBack = { backs += 1 })
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertIsDisplayed()

        compose.runOnUiThread {
            val down = AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_BACK, 0)
            compose.activity.dispatchKeyEvent(down)
            repeat(HELD_REPEATS) { count ->
                val flags = if (count == 0) AndroidKeyEvent.FLAG_LONG_PRESS else 0
                val held = AndroidKeyEvent.changeTimeRepeat(down, 50L * (count + 1), count + 1, flags)
                compose.activity.dispatchKeyEvent(held)
            }
            compose.activity.dispatchKeyEvent(AndroidKeyEvent.changeAction(down, AndroidKeyEvent.ACTION_UP))
        }
        settle()

        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        compose.runOnIdle { assertEquals("The held key did not also leave", 0, backs) }
    }

    private fun showReady(player: FakePlayer, resumePositionMillis: Long? = null, onBack: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlayerScreen(
                    state = readyState(resumePositionMillis = resumePositionMillis),
                    onBack = onBack,
                    onRetry = {},
                    onResume = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> player },
                )
            }
        }
        settle()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
    }

    /** A few frames: the key's state change, then the recomposition it causes. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
    }

    private fun row(id: Long, name: String, type: PutioFileType) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesFolder.Root.id,
        name = name,
        type = type,
        sizeBytes = 1L,
        createdAt = "2026-04-20T10:00:00Z",
    )

    private fun readyState(name: String = "Sintel.mp4", resumePositionMillis: Long? = null) = PlaybackState(
        target = PlaybackTarget(FilesItemId(9), name, PlaybackMediaType.VIDEO),
        content = PlaybackContent.Ready(source()),
        nextRequestValue = 2L,
        resumePositionMillis = resumePositionMillis,
    )

    private fun source(startFromSeconds: Double = 0.0) = PlaybackSource(
        fileId = 9,
        kind = PlaybackSourceKind.HLS,
        url = PutioCredentialUrl::class.java
            .getDeclaredConstructor(String::class.java)
            .newInstance(SOURCE_URL),
        startFromSeconds = startFromSeconds,
        subtitles = PlaybackSubtitles.None,
    )

    private companion object {
        const val SETTLE_MILLIS = 50L
        const val HELD_REPEATS = 10
        const val SOURCE_URL = "https://api.put.io/v2/files/9/hls/media.m3u8?token=t"
    }
}

@UnstableApi
private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var state = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_PREPARE,
                    COMMAND_SET_MEDIA_ITEM,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_TIMELINE,
                    COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    COMMAND_RELEASE,
                ).build(),
        ).build()
    val mediaItems = mutableListOf<MediaItem>()
    var startPositionMillis: Long? = null
    var prepared = false
    var released = false

    override fun getState(): State = state

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        this.mediaItems += mediaItems
        startPositionMillis = startPositionMs
        state = state.buildUpon()
            .setPlaylist(
                mediaItems.map {
                    MediaItemData.Builder(it.mediaId)
                        .setMediaItem(it)
                        .setDurationUs(DURATION_US)
                        .setIsSeekable(true)
                        .build()
                },
            )
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(startPositionMs)
            .build()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        state = state.buildUpon().setPlaybackState(STATE_READY).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        state = state.buildUpon().setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        state = state.buildUpon().setContentPositionMs(positionMs).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        return Futures.immediateVoidFuture()
    }

    fun advanceTo(positionMillis: Long) {
        state = state.buildUpon().setContentPositionMs(positionMillis).build()
        invalidateState()
    }

    fun fail(positionMillis: Long) {
        state = state.buildUpon()
            .setContentPositionMs(positionMillis)
            .setPlayerError(PlaybackException("Playback failed", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            .setPlaybackState(STATE_IDLE)
            .build()
        invalidateState()
    }

    private companion object {
        const val DURATION_US = 14L * 60L * 1_000_000L
    }
}
