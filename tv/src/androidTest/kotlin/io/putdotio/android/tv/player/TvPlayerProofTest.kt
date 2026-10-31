package io.putdotio.android.tv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
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
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.filesBrowserState
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.SUBTITLE_CUES_TAG
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.playbackAudioTracks
import io.putdotio.android.playback.playbackState
import io.putdotio.android.playback.playbackSubtitleTracks
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AccountSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.openTvTab
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
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
import io.putdotio.android.PutioFailure

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
        elapse(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + 1_500L)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        screenshot("03-playing-clean")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { !it.playWhenReady }
        compose.onNodeWithContentDescription("Play").assertExists()
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
        compose.onNodeWithContentDescription("Play").assertExists()
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
    fun resumeDialogBackLeavesThenResumeAndStartOverWithWriteBack() {
        val server = ProofPositionServer(startFromSeconds = SAVED_SECONDS)
        val factory = mountFilesWithResume(server)
        compose.onNodeWithContentDescription("Open Documents").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)

        // A saved position asks first; Resume is preferred.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(RESUME_TITLE).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(RESUME_LABEL).assertIsFocused()
        assertNull("No player before the choice", factory.player)
        screenshot("20-resume-focused")
        pause()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithText(RESTART_LABEL).assertIsFocused()
        screenshot("21-resume-restart-focused")
        pause()

        // Back leaves playback without a player, as mobile's and tv-native's prompts do.
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.runOnIdle { controller == null } }
        assertNull("No player after Back", factory.player)
        compose.onNodeWithContentDescription("Play $FIXTURE_TITLE").assertIsFocused()
        compose.runOnIdle { assertTrue("Back writes nothing", server.writes.isEmpty()) }
        elapse(DIALOG_EXIT_MILLIS)
        screenshot("22-back-left-playback")
        pause()

        // Asked again, Resume plays from the saved position.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(RESUME_TITLE).fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.isPlaying && factory.renderedFrame && it.currentPosition >= SAVED_SECONDS * 1_000L }
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        screenshot("22b-continued")

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

        // Again: the server now holds the exit position. Start over plays from zero.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(RESTART_LABEL).fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.isPlaying && factory.renderedFrame }
        compose.runOnIdle { assertTrue(factory.current().currentPosition < 10_000L) }
        screenshot("23-started-over")
        pause()
        leavePlayback()
        compose.waitUntil(10_000) { compose.runOnIdle { server.writes.size == 3 } }
        assertTrue("Starting over writes its own position", server.writes.last() < 10.0)

        // Resume plays from what the last playback saved.
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

    @Test
    fun aVideoFinishedWithinTenSecondsOfItsEndOpensAgainWithoutAsking() {
        val server = ProofPositionServer(startFromSeconds = NEAR_END_SAVED_SECONDS)
        val factory = mountFilesWithResume(server)
        compose.onNodeWithContentDescription("Open Documents").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(NEAR_END_LABEL).fetchSemanticsNodes().isNotEmpty() }
        screenshot("90-near-end-prompt")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.isPlaying && factory.renderedFrame }
        screenshot("91-playing-to-the-end")

        // Without autoplay the finished video leaves playback; its real end position is written.
        compose.waitUntil(FIXTURE_SECONDS.toLong() * 1_000L) { compose.runOnIdle { controller == null } }
        compose.waitUntil(10_000) {
            compose.runOnIdle { (server.writes.lastOrNull() ?: 0.0) >= FIXTURE_SECONDS - 1.0 }
        }
        compose.onNodeWithContentDescription("Play $FIXTURE_TITLE").assertIsFocused()
        screenshot("92-finished-back-on-the-row")
        pause()

        // Saved within 10 s of the end, it counts as finished: it starts over without asking.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.isPlaying && factory.renderedFrame }
        compose.onAllNodesWithText(RESUME_PREFIX, substring = true).assertCountEquals(0)
        compose.runOnIdle { assertTrue("Plays from the start", factory.current().currentPosition < 10_000L) }
        screenshot("93-opened-from-the-start")
        pause()
        leavePlayback()
    }

    @Test
    fun savedAudioContinuesWithoutAsking() {
        val fixture = arguments.getString("putio.tv.player.audioFixture")
        assumeTrue("Needs an audio fixture", fixture != null)
        val server = ProofPositionServer(startFromSeconds = SAVED_SECONDS)
        val factory = mountFilesWithResume(server, localSource(checkNotNull(fixture)), PlaybackMediaType.AUDIO)
        compose.onNodeWithContentDescription("Open Documents").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.isPlaying && it.currentPosition >= SAVED_SECONDS * 1_000L }
        compose.onAllNodesWithText(RESUME_PREFIX, substring = true).assertCountEquals(0)
        compose.runOnIdle {
            assertTrue(
                "Continued from the saved position",
                factory.current().currentPosition < (SAVED_SECONDS + 10) * 1_000L,
            )
        }
        screenshot("95-audio-continued")
        pause()
        leavePlayback()
        compose.onNodeWithContentDescription("Play $AUDIO_TITLE").assertIsFocused()
    }

    /**
     * Walks the redesign's player boards on a fixture with two audio tracks, two subtitle tracks
     * in the file and one put.io found: Resume, the paused controls, then the Audio, Subtitles,
     * Size and timing, and Speed sheets, each closed with Back before the controls.
     */
    @Test
    fun playerControlsAndSheetsFollowTheBoards() {
        val fixture = arguments.getString("putio.tv.player.boardsFixture")
        val found = arguments.getString("putio.tv.player.foundSubtitle")
        assumeTrue("Needs the boards fixture and its found subtitle", fixture != null && found != null)
        val source = localSource(requireNotNull(fixture)).copy(
            startFromSeconds = BOARD_RESUME_SECONDS,
            subtitles = PlaybackSubtitles.Sidecar(listOf(foundSubtitle(requireNotNull(found)))),
        )
        val factory = ProofPlayerFactory()
        var content by mutableStateOf<PlaybackContent>(PlaybackContent.AwaitingResume(source))
        compose.setContent {
            PutioTvTheme {
                TvPlayerScreen(
                    state = playbackState(
                        target = PlaybackTarget(FilesItemId(FIXTURE_FILE_ID), BOARD_TITLE, PlaybackMediaType.VIDEO),
                        content = content,
                        nextRequestValue = 2L,
                    ),
                    onBack = {},
                    onRetry = { error("Unexpected retry") },
                    onResume = { content = PlaybackContent.Ready(source) },
                    onRestart = { error("Unexpected restart") },
                    onPlayerFailure = { failure, _ -> error("Local playback failed: $failure") },
                    playerFactory = factory,
                    subtitleStartupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true),
                    autoplayNextVideo = true,
                    onPlaybackEnded = { false },
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(BOARD_RESUME_TITLE).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(RESUME_LABEL).assertIsFocused()
        elapse(DIALOG_EXIT_MILLIS)
        screenshot("androidtv-rde2-07-resume")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) {
            it.isPlaying && factory.renderedFrame && it.currentTracks.playbackAudioTracks().size == 2 &&
                it.currentTracks.playbackSubtitleTracks().size == 3
        }
        elapse(BOARD_PLAY_MILLIS)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { !it.playWhenReady }

        // English in the file, picked from the sheet.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithContentDescription(SUBTITLES, substring = true).assertIsSelected()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("In this file").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("English") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        awaitPlayer(factory) { selectedSubtitleLanguage(it) == "en" }
        elapse(DIALOG_EXIT_MILLIS)

        // Size and timing: the offset steps by 0.1 s from +0.1.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("In this file").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Size and timing") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Text size").fetchSemanticsNodes().isNotEmpty() }
        elapse(DIALOG_EXIT_MILLIS)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(BOARD_OFFSET_STEPS) { press(KeyEvent.KEYCODE_DPAD_CENTER) }
        compose.onNodeWithText("+0.5 s").assertExists()
        compose.onNodeWithContentDescription("Later by 0.1 seconds").assertIsFocused()
        screenshot("androidtv-st-p7-timing")

        // Back returns to Subtitles on the shown track.
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Medium · +0.5 s").fetchSemanticsNodes().isNotEmpty() }
        elapse(DIALOG_EXIT_MILLIS)
        compose.onNode(hasText("English") and hasClickAction()).assertIsFocused()
        screenshot("androidtv-rde2-09-subtitles")
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("In this file").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription(SUBTITLES, substring = true).assertIsSelected()

        // Audio: tracks by language with channels and codec; picking one closes the sheet.
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Stereo · AAC").fetchSemanticsNodes().isNotEmpty() }
        elapse(DIALOG_EXIT_MILLIS)
        screenshot("androidtv-st-p5-audio")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { selectedAudioLanguage(it) == "tr" }
        elapse(DIALOG_EXIT_MILLIS)
        compose.onNodeWithContentDescription("Audio: Türkçe").assertIsSelected()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Stereo · AAC").fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { selectedAudioLanguage(it) == "en" }
        elapse(DIALOG_EXIT_MILLIS)

        // Speed: + steps to the next preset and the sheet stays.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithContentDescription(SPEED, substring = true).assertIsSelected()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Speed").fetchSemanticsNodes().isNotEmpty() }
        elapse(DIALOG_EXIT_MILLIS)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.playbackParameters.speed == 1.25f }
        compose.onNodeWithContentDescription("Faster, 1.5×").assertIsFocused()
        screenshot("androidtv-st-p6-speed")
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Speed").fetchSemanticsNodes().isEmpty() }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Speed").fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.playbackParameters.speed == 1f }
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Speed").fetchSemanticsNodes().isEmpty() }

        // Up twice reaches play and pause; the paused controls never hide.
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithContentDescription("Play").assertIsSelected()
        elapse(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + DIALOG_EXIT_MILLIS)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertExists()
        compose.runOnIdle { assertFalse(factory.current().playWhenReady) }
        screenshot("androidtv-rde2-08-player")
    }

    private fun foundSubtitle(path: String): PlaybackSubtitle {
        val file = File(path).canonicalFile
        require(file.toPath().startsWith(requireNotNull(context.getExternalFilesDir(null)).canonicalFile.toPath()))
        val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(Uri.fromFile(file).toString())
        return PlaybackSubtitle(
            format = "srt",
            key = "found-tr",
            language = "Turkish",
            languageCode = "tur",
            name = "Türkçe",
            source = "opensubtitles",
            url = url,
        )
    }

    /**
     * The account's default subtitle (the master's `DEFAULT=YES` rendition, German here) starts
     * on even though the system's captions ask for English.
     */
    @Test
    fun automaticSubtitlesStartOnTheAccountsDefaultOverTheCaptionLanguage() {
        val fixture = arguments.getString("putio.tv.player.defaultSubtitleFixture")
        assumeTrue("Needs the two-subtitle fixture", fixture != null)
        val enabled = shellOutput("settings get secure accessibility_captioning_enabled")
        val locale = shellOutput("settings get secure accessibility_captioning_locale")
        shell("settings put secure accessibility_captioning_enabled 1")
        shell("settings put secure accessibility_captioning_locale en_US")
        try {
            val factory = mountFilesWithPlayer(
                SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true),
                localSource(requireNotNull(fixture)),
            )
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitPlayer(factory) {
                it.isPlaying && factory.renderedFrame && it.currentPosition > 1_000L &&
                    it.currentTracks.playbackSubtitleTracks().size == 2 && selectedSubtitle(it) != null
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(SUBTITLE_CUES_TAG).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { assertEquals("de", selectedSubtitleLanguage(factory.current())) }
            screenshot("70-account-default-subtitle")
            pause()
        } finally {
            restoreSecureSetting("accessibility_captioning_enabled", enabled)
            restoreSecureSetting("accessibility_captioning_locale", locale)
        }
    }

    /** `hide_subtitles` leaves no Subtitles chip and no subtitle on, whatever the stream has. */
    @Test
    fun hideSubtitlesLeavesNoSubtitlesChip() {
        val factory = mountFilesWithPlayer(SubtitleStartupPolicy(showSubtitles = false, autoSelectSubtitles = true))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) {
            it.isPlaying && factory.renderedFrame && it.currentPosition > 1_000L &&
                it.currentTracks.playbackAudioTracks().size == 2
        }
        // Down brings hidden controls back on play and pause; two more reach Audio, Right goes to Speed.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription(AUDIO, substring = true).assertIsSelected()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithContentDescription(SPEED, substring = true).assertIsSelected()
        compose.onNodeWithContentDescription(SUBTITLES, substring = true).assertDoesNotExist()
        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertDoesNotExist()
        compose.runOnIdle { assertNull(selectedSubtitle(factory.current())) }
        screenshot("80-hidden-subtitles-no-chip")
        pause()
    }

    private fun selectedSubtitle(player: Player) = player.currentTracks.playbackSubtitleTracks().singleOrNull { it.selected }

    private fun selectedSubtitleLanguage(player: Player) =
        selectedSubtitle(player)?.let { it.group.getFormat(it.trackIndex).language }

    private fun selectedAudioLanguage(player: Player) =
        player.currentTracks.playbackAudioTracks().singleOrNull { it.selected }
            ?.let { it.group.getFormat(it.trackIndex).language }

    /**
     * The conversion interstitial on a fake conversion source, started once on opening without a
     * press, then the remote's play/pause key and the system's media controls through the
     * published session, then a failed resolution that Try again recovers from.
     */
    @Test
    fun conversionThenSessionControlsAndARecoverableError() {
        val conversionStarts = AtomicInteger()
        val factory = mountFilesWithSessionRoute(conversionStarts)
        compose.onNodeWithContentDescription("Open Documents").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("Play $CONVERTING_TITLE").assertIsFocused()

        // Opening it starts the conversion without a press; then queued and running, read again every 3 s.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitConversionStatus("Starting…")
        compose.onNodeWithText(
            "This video isn’t in a format this app can play yet, so its conversion has started. It plays here once it finishes.",
        ).assertExists()
        screenshot("49-conversion-starting")
        awaitConversionStatus("In queue…")
        compose.onNodeWithText("Conversion status").assertExists()
        screenshot("50-conversion-queued")
        awaitConversionStatus("35%")
        screenshot("51-conversion-35")
        awaitConversionStatus("80%")
        screenshot("52-conversion-80")
        awaitPlayer(factory) { it.isPlaying && factory.renderedFrame && it.currentPosition > 1_000L }
        screenshot("53-converted-playing")
        assertEquals("One conversion start per opening", 1, conversionStarts.get())
        elapse(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + 1_000L)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()

        // The remote's play/pause key, as `adb shell input keyevent` sends it.
        shell("input keyevent KEYCODE_MEDIA_PLAY_PAUSE")
        awaitPlayer(factory) { !it.playWhenReady }
        compose.onNodeWithContentDescription("Play").assertExists()
        screenshot("54-remote-paused")
        pause()
        shell("input keyevent KEYCODE_MEDIA_PLAY_PAUSE")
        awaitPlayer(factory) { it.isPlaying }
        screenshot("55-remote-playing")

        // The system sees the session, and its media controls reach the player through it.
        val system = sessionController()
        assertEquals(CONVERTING_TITLE, system.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
        assertEquals(android.media.session.PlaybackState.STATE_PLAYING, system.playbackState?.state)
        elapse(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + 1_000L)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        system.transportControls.pause()
        awaitPlayer(factory) { !it.playWhenReady }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(TV_PLAYER_CONTROLS_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Play").assertExists()
        screenshot("56-system-paused")
        pause()
        system.transportControls.play()
        awaitPlayer(factory) { it.isPlaying }
        screenshot("57-system-playing")
        leavePlayback()
        compose.waitUntil(5_000) { activeSessions().isEmpty() }

        // A failed resolution explains itself; Try again resolves again and plays.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("Play $OFFLINE_TITLE").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Check the network and try again.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Try again").assertIsFocused()
        screenshot("58-network-error")
        pause()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.isPlaying && factory.renderedFrame && it.currentPosition > 1_000L }
        screenshot("59-retried-playing")
        leavePlayback()
        compose.onNodeWithContentDescription("Play $OFFLINE_TITLE").assertIsFocused()
        screenshot("60-back-on-files-row")
        pause()
    }

    private fun awaitConversionStatus(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(TV_CONVERSION_STATUS_TAG).fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == text
            }
        }
    }

    /** Runs [command] as the shell user, as `adb shell` does, and waits for it to finish. */
    private fun shell(command: String) {
        shellOutput(command)
        compose.waitForIdle()
    }

    private fun restoreSecureSetting(key: String, value: String) =
        shell(if (value.isEmpty()) "settings delete secure $key" else "settings put secure $key $value")

    private fun shellOutput(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
            .decodeToString().trim().takeUnless { it == "null" }.orEmpty()
    }

    /** This app's sessions as the system's media controls see them. */
    private fun activeSessions(): List<android.media.session.MediaController> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
        return try {
            context.getSystemService(android.media.session.MediaSessionManager::class.java)
                .getActiveSessions(null)
                .filter { it.packageName == context.packageName }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun sessionController(): android.media.session.MediaController {
        compose.waitUntil(5_000) { activeSessions().size == 1 }
        return activeSessions().single()
    }

    /**
     * Two rows on the real session route, without write-back: one with no conversion requested
     * whose start, counted in [conversionStarts], takes two seconds before it runs (queued,
     * 35 %, 80 %, then the local fixture), and one whose first resolution fails with a network
     * error before the fixture resolves.
     */
    private fun mountFilesWithSessionRoute(conversionStarts: AtomicInteger): ProofPlayerFactory {
        val source = localSource()
        val factory = ProofPlayerFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val conversion = ArrayDeque(
            listOf(
                PlaybackResolution.Conversion(PlaybackConversionState.NotAvailable),
                PlaybackResolution.Conversion(PlaybackConversionState.Queued),
                PlaybackResolution.Conversion(PlaybackConversionState.Converting(35.0)),
                PlaybackResolution.Conversion(PlaybackConversionState.Converting(80.0)),
            ),
        )
        var offlineFailed = false
        val repository = object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
                when {
                    target.fileId.value == FIXTURE_FILE_ID && conversion.isNotEmpty() ->
                        PlaybackRepositoryResult.Success(conversion.removeFirst())
                    target.fileId.value == OFFLINE_FILE_ID && !offlineFailed -> {
                        offlineFailed = true
                        PlaybackRepositoryResult.Failure(
                            PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IOException("proof: offline"))),
                        )
                    }
                    else -> PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source))
                }

            override suspend fun startConversion(
                target: PlaybackTarget,
            ): PlaybackRepositoryResult<PlaybackResolution> {
                check(target.fileId.value == FIXTURE_FILE_ID)
                conversionStarts.incrementAndGet()
                delay(2_000L)
                return resolve(target)
            }

            override suspend fun findNextVideo(target: PlaybackTarget) = error("No next video expected")
        }
        val files = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(
                        listOf(
                            row(1, "Documents", PutioFileType.FOLDER),
                            row(FIXTURE_FILE_ID, CONVERTING_TITLE, PutioFileType.VIDEO),
                            row(OFFLINE_FILE_ID, OFFLINE_TITLE, PutioFileType.VIDEO),
                        ),
                        FilesPaging.Complete,
                    ),
                ),
            ),
            nextRequestValue = 1L,
        )
        val focusMemory = mutableMapOf<Long, Long>()
        compose.setContent {
            PutioTvTheme {
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
        compose.openTvTab("Files")
        return factory
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
    private fun mountFilesWithResume(
        server: ProofPositionServer,
        source: PlaybackSource = localSource(),
        mediaType: PlaybackMediaType = PlaybackMediaType.VIDEO,
    ): ProofPlayerFactory {
        val factory = ProofPlayerFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget) = PlaybackRepositoryResult.Success(
                PlaybackResolution.Ready(source.copy(startFromSeconds = server.startFromSeconds), useStartFrom = true),
            )

            override suspend fun findNextVideo(target: PlaybackTarget) = error("No next video expected")
        }
        val reporting = TvPlaybackReporting(
            scope = scope,
            settings = MutableStateFlow(resumeOnSettings()),
            sessionCurrent = { true },
            write = server::write,
            onSaved = { _, _ -> },
        )
        val media = if (mediaType == PlaybackMediaType.AUDIO) {
            row(FIXTURE_FILE_ID, AUDIO_TITLE, PutioFileType.AUDIO)
        } else {
            row(FIXTURE_FILE_ID, FIXTURE_TITLE, PutioFileType.VIDEO)
        }
        val files = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(
                        listOf(row(1, "Documents", PutioFileType.FOLDER), media, row(3, "notes.txt", PutioFileType.TEXT)),
                        FilesPaging.Complete,
                    ),
                ),
            ),
            nextRequestValue = 1L,
        )
        val focusMemory = mutableMapOf<Long, Long>()
        compose.setContent {
            PutioTvTheme {
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
                                        PlaybackTarget(item.id, item.name, mediaType, FIXTURE_SECONDS),
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
        compose.openTvTab("Files")
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
    private fun mountFilesWithPlayer(
        subtitleStartupPolicy: SubtitleStartupPolicy? = null,
        source: PlaybackSource = localSource(),
    ): ProofPlayerFactory {
        val factory = ProofPlayerFactory()
        val video = row(FIXTURE_FILE_ID, FIXTURE_TITLE, PutioFileType.VIDEO)
        val files = filesBrowserState(
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
            PutioTvTheme {
                TvPlaybackLayer(
                    playing = playing != null,
                    player = {
                        val item = checkNotNull(playing)
                        TvPlayerScreen(
                            state = playbackState(
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
                            subtitleStartupPolicy = subtitleStartupPolicy,
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
        compose.openTvTab("Files")
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

    private fun localSource(
        fixture: String = requireNotNull(arguments.getString("putio.tv.player.fixture")),
    ): PlaybackSource {
        val file = File(fixture).canonicalFile
        require(file.toPath().startsWith(requireNotNull(context.getExternalFilesDir(null)).canonicalFile.toPath()))
        require(file.isFile && file.canRead()) { "Fixture is not readable: $file" }
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
        const val CONVERTING_TITLE = "Needs conversion.avi"
        const val OFFLINE_TITLE = "Offline first.mp4"
        const val OFFLINE_FILE_ID = 9_340_002L
        const val STEP_PAUSE_MILLIS = 1_500L
        const val ELAPSE_STEP_MILLIS = 100L
        const val FIXTURE_SECONDS = 90.0
        const val SAVED_SECONDS = 45.0
        const val NEAR_END_SAVED_SECONDS = 70.0
        const val NEAR_END_LABEL = "Resume from 1:10?"
        const val AUDIO_TITLE = "Harbor tune.m4a"
        const val POSITION_INTERVAL_MILLIS = 15_000L
        const val RESUME_TITLE = "Resume from 0:45?"
        const val RESUME_LABEL = "Resume"
        const val RESTART_LABEL = "Start over"
        const val RESUME_PREFIX = "Resume from"
        const val AUDIO = "Audio:"
        const val SUBTITLES = "Subtitles:"
        const val SPEED = "Playback speed"
        const val BOARD_TITLE = "TV player proof.mkv"
        const val BOARD_RESUME_SECONDS = 190.0
        const val BOARD_RESUME_TITLE = "Resume from 3:10?"
        const val BOARD_OFFSET_STEPS = 5
        const val BOARD_PLAY_MILLIS = 1_500L
        const val DIALOG_EXIT_MILLIS = 500L
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

/** The production TV player and media session, observed for its first rendered frame and its release. */
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

    override fun publish(context: Context, player: Player): java.io.Closeable =
        DefaultTvPlayerFactory.publish(context, player)
}
