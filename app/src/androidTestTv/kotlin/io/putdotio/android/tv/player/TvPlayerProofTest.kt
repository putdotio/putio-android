package io.putdotio.android.tv.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AccountSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Controlled-state TV playback proof: a fixed Files listing, the real TV player screen and
 * ExoPlayer streaming a caller-owned local HLS or MP4 fixture, driven with D-pad key events.
 * No API calls; the listing and the resolved source stand in for a signed-in session.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlayerProofTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(
                    "TV player proof requires opt-in",
                    arguments.getString("putio.tv.player.enabled") == "true",
                )
                runId()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun selectPlaysTheFixtureAndBackReturnsToItsRow() {
        val factory = mountFilesWithPlayer()
        compose.onNodeWithContentDescription("Open Documents").assertIsFocused()
        pause()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("Play $FIXTURE_TITLE").assertIsFocused()
        screenshot("01-files-row-focused")
        pause()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) {
            it.playbackState == Player.STATE_READY && it.isPlaying && factory.renderedFrame &&
                it.currentPosition > 1_000L && it.videoSize.width > 0
        }
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        screenshot("02-playing-controls")
        // Controls hide three seconds into playback.
        elapse(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + 1_500L)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        screenshot("03-playing-clean")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { !it.playWhenReady }
        compose.onNodeWithContentDescription("Paused").assertExists()
        screenshot("04-paused")
        pause()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.isPlaying }
        pause()

        val player = factory.current()
        // The first Back hides the controls; the second leaves.
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.runOnIdle { playing == null } }
        compose.onNodeWithContentDescription("Play $FIXTURE_TITLE").assertIsFocused()
        compose.runOnIdle {
            assertNull(playing)
            assertTrue("Leaving playback releases the player", factory.released(player))
        }
        screenshot("05-back-on-files-row")
        pause()
    }

    @Test
    fun dpadScrubbingAndBackWalkTheOverlayStack() {
        val factory = mountFilesWithPlayer()
        compose.onNodeWithContentDescription("Open Documents").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) {
            it.playbackState == Player.STATE_READY && it.isPlaying && factory.renderedFrame &&
                it.currentPosition > 1_000L
        }
        elapse(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + 1_000L)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        screenshot("10-playing-clean")

        // Right twice: the first press reveals the controls, pauses and targets +15 s; the second
        // grows the step when it lands inside the press window.
        val before = compose.runOnIdle { factory.current().currentPosition }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPlayer(factory) { !it.playWhenReady }
        val target = shownElapsedSeconds()
        assertTrue("Scrub target $target s from ${before / 1_000L} s", target * 1_000L >= before + 30_000L - 1_000L)
        val paused = compose.runOnIdle { factory.current().currentPosition }
        assertTrue("Nothing seeks before the commit", paused < target * 1_000L - 10_000L)
        screenshot("11-seek-mode")
        pause()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.isPlaying && it.currentPosition >= target * 1_000L - 1_000L }
        screenshot("12-seek-committed")
        pause()

        // Rewind scrubs back; Back dismisses seek mode without seeking and resumes playback.
        val committed = compose.runOnIdle { factory.current().currentPosition }
        press(KeyEvent.KEYCODE_MEDIA_REWIND)
        awaitPlayer(factory) { !it.playWhenReady }
        assertTrue(shownElapsedSeconds() * 1_000L <= committed - 14_000L)
        screenshot("13-seek-mode-rewind")
        pause()
        press(KeyEvent.KEYCODE_BACK)
        awaitPlayer(factory) { it.isPlaying }
        val resumed = compose.runOnIdle { factory.current().currentPosition }
        assertTrue("Dismissing seek mode keeps the time", resumed >= committed)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertExists()
        screenshot("14-seek-dismissed")
        pause()

        // Back hides the playing controls; playback continues and the player keeps focus.
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        awaitPlayer(factory) { it.isPlaying }
        assertNotNull("Still playing", playing)
        screenshot("15-controls-dismissed")
        pause()

        // Paused controls dismiss without resuming or leaving.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { !it.playWhenReady }
        compose.onNodeWithContentDescription("Paused").assertExists()
        screenshot("16-paused-controls")
        pause()
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        elapse(STEP_PAUSE_MILLIS)
        compose.runOnIdle {
            assertFalse("Still paused", factory.current().playWhenReady)
            assertNotNull("Still in playback", playing)
        }
        screenshot("17-paused-clean")

        // Nothing left: Back leaves to the row, once.
        val player = factory.current()
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.runOnIdle { playing == null } }
        compose.onNodeWithContentDescription("Play $FIXTURE_TITLE").assertIsFocused()
        compose.runOnIdle { assertTrue(factory.released(player)) }
        screenshot("18-back-on-files-row")
        pause()
    }

    @Test
    fun resumeDialogContinueStartOverAndBackWithWriteBack() {
        val server = ProofPositionServer(startFromSeconds = SAVED_SECONDS)
        val factory = mountFilesWithResume(server)
        compose.onNodeWithContentDescription("Open Documents").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)

        // A saved position asks first; Continue is preferred and the bar shows where it starts.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(CONTINUE_LABEL).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(CONTINUE_LABEL).assertIsFocused()
        assertNull("No player before the choice", factory.player)
        screenshot("20-resume-continue-focused")
        pause()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText(RESTART_LABEL).assertIsFocused()
        screenshot("21-resume-restart-focused")
        pause()

        // Back dismisses the dialog and continues from the saved position.
        press(KeyEvent.KEYCODE_BACK)
        awaitPlayer(factory) { it.isPlaying && factory.renderedFrame && it.currentPosition >= SAVED_SECONDS * 1_000L }
        assertNotNull("Back stays in playback", controller)
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        screenshot("22-back-continued")

        // Sixteen seconds of playback write once, not once per tick; leaving writes once more.
        elapse(POSITION_INTERVAL_MILLIS + 1_000L)
        compose.runOnIdle { assertEquals(1, server.writes.size) }
        leavePlayback()
        compose.waitUntil(10_000) { compose.runOnIdle { server.writes.size == 2 } }
        val (periodic, exit) = server.writes
        assertTrue("Periodic write $periodic s", periodic >= SAVED_SECONDS + 14.0)
        assertTrue("Exit write $exit s after $periodic s", exit >= periodic)
        compose.onNodeWithContentDescription("Play $FIXTURE_TITLE").assertIsFocused()
        pause()

        // Again: the server now holds the exit position. Start from the beginning plays from zero.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(RESTART_LABEL).fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.isPlaying && factory.renderedFrame }
        compose.runOnIdle { assertTrue(factory.current().currentPosition < 10_000L) }
        screenshot("23-started-over")
        pause()
        leavePlayback()
        compose.waitUntil(10_000) { compose.runOnIdle { server.writes.size == 3 } }
        assertTrue("Starting over writes its own position", server.writes.last() < 10.0)

        // Continue plays from what the last playback saved.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(RESUME_PREFIX, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("24-resume-after-start-over")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.isPlaying && it.currentPosition >= (server.writes.last() * 1_000L).toLong() - 1_000L }
        screenshot("25-continued")
        pause()
        leavePlayback()
        compose.onNodeWithContentDescription("Play $FIXTURE_TITLE").assertIsFocused()
        screenshot("26-back-on-files-row")
    }

    private var playing by mutableStateOf<FilesItem?>(null)
    private var controller by mutableStateOf<PlaybackController?>(null)

    /** Back hides the controls if they are up, then leaves; never a Back past the player. */
    private fun leavePlayback() {
        press(KeyEvent.KEYCODE_BACK)
        if (compose.runOnIdle { controller != null }) press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.runOnIdle { controller == null } }
    }

    /**
     * The Files listing on the real session route: a [PlaybackController] per play, resolving
     * the local fixture with [server]'s saved position, and TV write-back into [server].
     */
    private fun mountFilesWithResume(server: ProofPositionServer): ProofPlayerFactory {
        val source = localSource()
        val factory = ProofPlayerFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget) = PlaybackRepositoryResult.Success(
                PlaybackResolution.Ready(source.copy(startFromSeconds = server.startFromSeconds), useStartFrom = true),
            )

            override suspend fun findNextVideo(target: PlaybackTarget) = error("No autoplay on TV")
        }
        val reporting = TvPlaybackReporting(
            scope = scope,
            settings = MutableStateFlow(resumeOnSettings()),
            sessionCurrent = { true },
            write = server::write,
            onSaved = { _, _ -> },
        )
        val video = row(FIXTURE_FILE_ID, FIXTURE_TITLE, PutioFileType.VIDEO)
        val files = FilesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(
                        listOf(row(1, "Documents", PutioFileType.FOLDER), video, row(3, "notes.txt", PutioFileType.TEXT)),
                        FilesPaging.Complete,
                    ),
                ),
            ),
            nextRequestValue = 1L,
        )
        val focusMemory = mutableMapOf<Long, Long>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackLayer(
                    playing = controller != null,
                    player = {
                        TvPlaybackRoute(
                            controller = checkNotNull(controller),
                            onExit = {
                                controller?.close()
                                controller = null
                            },
                            onSessionRejected = { error("Unexpected rejection") },
                            playerFactory = factory,
                            reporter = reporting,
                        )
                    },
                ) {
                    TvShell(
                        account = TvAccount(userId = 1, username = "proof", email = "proof@example.invalid"),
                        onSignOut = {},
                        filesPane = { paneFocus ->
                            TvFilesScreen(
                                state = files,
                                onEvent = { true },
                                onPlayMedia = { item ->
                                    reporting.startPlayback()
                                    controller = PlaybackController(
                                        PlaybackTarget(item.id, item.name, PlaybackMediaType.VIDEO, FIXTURE_SECONDS),
                                        repository,
                                        scope,
                                    )
                                },
                                modifier = Modifier.focusRequester(paneFocus),
                                focusMemory = focusMemory,
                            )
                        },
                    )
                }
            }
        }
        return factory
    }

    private fun resumeOnSettings(): AccountSettingsState =
        AccountSettingsReducer.reduce(
            AccountSettingsReducer.start().state,
            AccountSettingsEvent.LoadSucceeded(
                AccountSettingsRequestId(1),
                AccountSettingsPreferences(
                    historyEnabled = true,
                    trashEnabled = true,
                    showSubtitles = true,
                    autoSelectSubtitles = true,
                    resumePlayback = true,
                ),
            ),
        ).state

    /** A fixed Files listing whose media row plays the local fixture on the production player. */
    private fun mountFilesWithPlayer(): ProofPlayerFactory {
        val source = localSource()
        val factory = ProofPlayerFactory()
        val video = row(FIXTURE_FILE_ID, FIXTURE_TITLE, PutioFileType.VIDEO)
        val files = FilesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(
                        listOf(row(1, "Documents", PutioFileType.FOLDER), video, row(3, "notes.txt", PutioFileType.TEXT)),
                        FilesPaging.Complete,
                    ),
                ),
            ),
            nextRequestValue = 1L,
        )
        val focusMemory = mutableMapOf<Long, Long>()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackLayer(
                    playing = playing != null,
                    player = {
                        val item = checkNotNull(playing)
                        TvPlayerScreen(
                            state = PlaybackState(
                                target = PlaybackTarget(item.id, item.name, PlaybackMediaType.VIDEO),
                                content = PlaybackContent.Ready(source),
                                nextRequestValue = 2L,
                            ),
                            onBack = { playing = null },
                            onRetry = { error("Unexpected retry") },
                            onResume = { error("Unexpected resume") },
                            onRestart = { error("Unexpected restart") },
                            onPlayerFailure = { failure, _ -> error("Local playback failed: $failure") },
                            playerFactory = factory,
                        )
                    },
                ) {
                    TvShell(
                        account = TvAccount(userId = 1, username = "proof", email = "proof@example.invalid"),
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
        return factory
    }

    /** The elapsed label, which shows a pending scrub's target. */
    private fun shownElapsedSeconds(): Long {
        val text = compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }
        return text.split(":").fold(0L) { total, part -> total * 60L + part.toLong() }
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    /** Lets a concurrent screen recording show each step. */
    private fun pause() = elapse(STEP_PAUSE_MILLIS)

    /**
     * Waits in real time while keeping the rule's virtual clock in step, so the player's
     * own timers (auto-hide, position polling) run as they would outside a test.
     */
    private fun elapse(millis: Long) {
        var remaining = millis
        while (remaining > 0) {
            val step = minOf(ELAPSE_STEP_MILLIS, remaining)
            Thread.sleep(step)
            compose.mainClock.advanceTimeBy(step)
            remaining -= step
        }
    }

    private fun awaitPlayer(factory: ProofPlayerFactory, predicate: (Player) -> Boolean) {
        compose.waitUntil(30_000) {
            compose.runOnIdle {
                factory.player?.let {
                    check(it.playerError == null) { "Playback failed: ${it.playerError?.errorCodeName}" }
                    predicate(it)
                } == true
            }
        }
    }

    private fun localSource(): PlaybackSource {
        val file = File(requireNotNull(arguments.getString("putio.tv.player.fixture"))).canonicalFile
        require(file.isFile && file.canRead()) { "Fixture is not readable: $file" }
        require(file.toPath().startsWith(requireNotNull(context.getExternalFilesDir(null)).canonicalFile.toPath()))
        // The SDK owns production URLs. This proof reads only its caller-owned local fixture.
        val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(Uri.fromFile(file).toString())
        return PlaybackSource(
            fileId = FIXTURE_FILE_ID,
            kind = if (file.extension == "m3u8") PlaybackSourceKind.HLS else PlaybackSourceKind.ORIGINAL,
            url = url,
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )
    }

    private fun row(id: Long, name: String, type: PutioFileType) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesFolder.Root.id,
        name = name,
        type = type,
        sizeBytes = 1L,
        createdAt = "2026-09-30T10:00:00Z",
    )

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-player-proof-${runId()}")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.player.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val FIXTURE_FILE_ID = 9_340_001L
        const val FIXTURE_TITLE = "TV player proof.mp4"
        const val STEP_PAUSE_MILLIS = 1_500L
        const val ELAPSE_STEP_MILLIS = 100L
        const val FIXTURE_SECONDS = 90.0
        const val SAVED_SECONDS = 45.0
        const val POSITION_INTERVAL_MILLIS = 15_000L
        const val CONTINUE_LABEL = "Continue playing from 00:45"
        const val RESTART_LABEL = "Start from the beginning"
        const val RESUME_PREFIX = "Continue playing from"
    }
}

/** Stands in for the account's `start_from`: resolutions read it and write-back updates it. */
private class ProofPositionServer(var startFromSeconds: Double) {
    val writes = mutableListOf<Double>()

    @Suppress("UNUSED_PARAMETER")
    suspend fun write(fileId: Long, seconds: Double): PlaybackRepositoryResult<Unit> {
        writes += seconds
        startFromSeconds = seconds
        return PlaybackRepositoryResult.Success(Unit)
    }
}

/** The production TV player, observed for its first rendered frame and its release. */
@UnstableApi
private class ProofPlayerFactory : TvPlayerFactory {
    var player: Player? = null
        private set
    var renderedFrame = false
        private set
    fun current(): Player = checkNotNull(player)

    fun released(player: Player): Boolean = (player as ExoPlayer).isReleased

    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        DefaultTvPlayerFactory.create(context, mediaType).also { created ->
            player = created
            renderedFrame = false
            created.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    renderedFrame = true
                }
            })
        }
}
