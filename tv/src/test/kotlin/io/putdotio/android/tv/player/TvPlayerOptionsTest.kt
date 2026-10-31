package io.putdotio.android.tv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.Looper
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.tv.setTvContent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SUBTITLE_CUES_TAG
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.playbackState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import io.putdotio.android.playback.PlaybackState

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlayerOptionsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theChipsFollowTheTracksAndSpeedIsAlwaysThere() {
        val player = TrackPlayer(audio = null, text = null)
        show(player)
        chip(SPEED).assertIsDisplayed()
        chip(AUDIO).assertDoesNotExist()
        chip(SUBTITLES).assertIsDisplayed().assert(describedAs("Subtitles: Off"))

        compose.runOnIdle { player.replaceTracks(audio = twoAudio(period = 2), text = oneText()) }
        settle()
        chip(AUDIO).assertIsDisplayed().assert(describedAs("Audio: Italiano"))
        chip(SPEED).assertIsDisplayed().assert(describedAs("Playback speed 1×"))
    }

    @Test
    fun downReachesTheChipsLeftAndRightWalkThemAndUpReturnsToScrubbing() {
        val player = TrackPlayer()
        show(player, readyState(resumePositionMillis = 60_000L))
        compose.onNodeWithContentDescription("Pause").assertIsSelected()

        key(Key.DirectionDown)
        key(Key.DirectionDown)
        chip(AUDIO).assertIsSelected()
        key(Key.DirectionRight)
        chip(SUBTITLES).assertIsSelected()
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        chip(SPEED).assertIsSelected()
        chip(AUDIO).assertIsNotSelected()
        compose.runOnIdle { assertTrue("Walking the chips never scrubs", player.playWhenReady) }

        key(Key.DirectionUp)
        chip(SPEED).assertIsNotSelected()
        key(Key.DirectionLeft)
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("0:45")
        compose.runOnIdle { assertFalse("Left on the seek bar scrubs", player.playWhenReady) }
    }

    @Test
    fun theCirclesSkipTenSecondsAndToggle() {
        val player = TrackPlayer()
        show(player, readyState(resumePositionMillis = 60_000L))
        key(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Back 10 seconds").assertIsSelected()
        key(Key.DirectionCenter)
        compose.runOnIdle { assertTrue("Back 10 s", player.currentPosition in 50_000L..51_000L) }
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        compose.onNodeWithContentDescription("Forward 10 seconds").assertIsSelected()
        key(Key.DirectionCenter)
        compose.runOnIdle { assertTrue("Forward 10 s", player.currentPosition in 60_000L..61_500L) }
        key(Key.DirectionLeft)
        key(Key.DirectionCenter)
        compose.runOnIdle { assertFalse(player.playWhenReady) }
        compose.onNodeWithContentDescription("Play").assertIsSelected()
    }

    @Test
    fun rewindOnAChipPullsFocusBackToTheSeekBarAndScrubs() {
        val player = TrackPlayer()
        show(player, readyState(resumePositionMillis = 60_000L))
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        chip(AUDIO).assertIsSelected()

        key(Key.MediaRewind)
        chip(AUDIO).assertIsNotSelected()
        compose.onNodeWithTag(TV_PLAYER_SEEK_BAR_TAG).assertIsSelected()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("0:45")
        compose.runOnIdle { assertFalse(player.playWhenReady) }
    }

    @Test
    fun speedStepsThroughThePresetsAndBackClosesTheSheetBeforeTheControlsThenExits() {
        val player = TrackPlayer()
        var exits = 0
        show(player, onBack = { exits += 1 })
        openSheet(SPEED, rightPresses = 2)
        compose.onNodeWithText("Speed").assertIsDisplayed()
        settle()
        compose.onNodeWithContentDescription("Faster, 1.25×").assertIsFocused()
        choose("+")
        compose.runOnIdle { assertEquals(1.25f, player.playbackParameters.speed) }
        choose("1.5×")
        compose.runOnIdle { assertEquals(1.5f, player.playbackParameters.speed) }
        compose.onNodeWithText("Speed").assertIsDisplayed()
        chip(SPEED).assert(describedAs("Playback speed 1.5×"))

        backInDialog()
        compose.onNodeWithText("Speed").assertDoesNotExist()
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertIsDisplayed()
        chip(SPEED).assertIsSelected()
        compose.runOnIdle {
            assertEquals(0, exits)
            assertTrue(player.playWhenReady)
            assertEquals(1.5f, player.playbackParameters.speed)
        }
        key(Key.DirectionLeft)
        chip(SUBTITLES).assertIsSelected()

        back()
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, exits) }
        back()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test
    fun anOpenSheetKeepsTheControlsPastTheAutoHideDelay() {
        val player = TrackPlayer()
        show(player)
        openSheet(SPEED, rightPresses = 2)
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS * 2)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Speed").assertIsDisplayed()
    }

    @Test
    fun sizeAndTimingOpensFromSubtitlesAndBackReturnsThere() {
        val player = TrackPlayer()
        show(player, policy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true))
        openSheet(SUBTITLES, rightPresses = 1)
        compose.onNodeWithText("In this file").assertIsDisplayed()
        compose.onNodeWithText("Medium · 0.0 s").assertIsDisplayed()
        choose("Size and timing")
        compose.onNodeWithText("Text size").assertIsDisplayed()
        choose("+0.1")
        choose("+0.1")
        compose.onNodeWithText("+0.2 s").assertIsDisplayed()
        choose("L")
        backInDialog()
        compose.onNodeWithText("Large · +0.2 s").assertIsDisplayed()
        compose.onNode(hasText("Size and timing") and hasClickAction()).assertIsFocused()
        backInDialog()
        compose.onNodeWithText("In this file").assertDoesNotExist()
        chip(SUBTITLES).assertIsSelected()
    }

    @Test
    fun theChosenAudioTrackSurvivesATrackChange() {
        val player = TrackPlayer()
        show(player)
        openSheet(AUDIO)
        compose.onNodeWithText("Audio").assertIsDisplayed()
        choose("English")
        settle()
        compose.runOnIdle { assertEquals(1, player.selectedAudioIndex()) }

        compose.runOnIdle { player.replaceTracks(audio = twoAudio(period = 2), text = twoText(period = 2)) }
        settle()
        compose.runOnIdle { assertEquals(1, player.selectedAudioIndex()) }
    }

    @Test
    fun audioTracksOfUndeterminedLanguageTakeTheirNumbers() {
        val player = TrackPlayer(
            audio = TrackGroup(
                "audio-und",
                audioFormat("a-1", C.LANGUAGE_UNDETERMINED),
                audioFormat("a-2", "und", label = " "),
            ),
            text = null,
        )
        show(player)
        openSheet(AUDIO)
        compose.onNodeWithText("Audio track 1").assertIsDisplayed()
        compose.onNodeWithText("Audio track 2").assertIsDisplayed()
        compose.onNodeWithText("und").assertDoesNotExist()
    }

    @Test
    fun inFileSubtitlesNameTheirFormatOrTheMissingLanguage() {
        val player = TrackPlayer(
            text = TrackGroup(
                "text-in-file",
                Format.Builder()
                    .setId("t-en")
                    .setLanguage("en")
                    .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                    .setCodecs(MimeTypes.APPLICATION_SUBRIP)
                    .build(),
                textFormat("t-und", C.LANGUAGE_UNDETERMINED, label = null),
            ),
        )
        show(player)
        openSheet(SUBTITLES, rightPresses = 1)
        compose.onNodeWithText("SRT").assertIsDisplayed()
        compose.onNodeWithText("Track 2").assertIsDisplayed()
        compose.onNodeWithText("Embedded · language not set").assertIsDisplayed()
    }

    @Test
    fun aLongTrackNameLeavesRoomForTheOtherChips() {
        val player = TrackPlayer(
            audio = TrackGroup(
                "audio-long",
                audioFormat("a-1", C.LANGUAGE_UNDETERMINED, label = "Director commentary ".repeat(12)),
            ),
        )
        show(player)
        listOf(SUBTITLES, SPEED).forEach { prefix ->
            chip(prefix).assertIsDisplayed().assertWidthIsAtLeast(32.dp)
        }
    }

    @Test
    fun subtitlesOffStaysOffAndDrawsNothingAcrossSeeksAndTrackChanges() {
        val player = TrackPlayer()
        show(player, policy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true))
        compose.runOnIdle { player.emitCue("Automatic cue") }
        settle()
        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertIsDisplayed()
        chip(SUBTITLES).assert(describedAs("Subtitles: English"))

        openSheet(SUBTITLES, rightPresses = 1)
        choose("Off")
        settle()
        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertDoesNotExist()
        chip(SUBTITLES).assert(describedAs("Subtitles: Off"))

        compose.runOnIdle {
            player.seekTo(300_000L)
            player.replaceTracks(audio = twoAudio(period = 2), text = twoText(period = 2))
            player.emitCue("Late cue")
        }
        settle()
        compose.runOnIdle {
            assertTrue(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
            assertNull(player.selectedTextIndex())
        }
        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertDoesNotExist()

        key(Key.DirectionCenter)
        choose("Deutsch")
        settle()
        compose.runOnIdle { player.replaceTracks(audio = twoAudio(period = 3), text = twoText(period = 3)) }
        settle()
        compose.runOnIdle {
            assertTrue(C.TRACK_TYPE_TEXT !in player.trackSelectionParameters.disabledTrackTypes)
            assertEquals(1, player.selectedTextIndex())
        }
        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertIsDisplayed()
    }

    @Test
    fun subtitleTracksThatReadTheSameTakeTheirNumbers() {
        val player = TrackPlayer(
            text = TrackGroup("text-twins", textFormat("t-en-1", "en", null), textFormat("t-en-2", "en", null)),
        )
        show(player, policy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true))
        openSheet(SUBTITLES, rightPresses = 1)
        compose.onNodeWithText("English (track 1)").assertIsDisplayed()
        compose.onNodeWithText("English (track 2)").assertIsDisplayed()
    }

    @Test
    fun aDelayedTrackStopsShowingOnceTheViewerSwitchesTracks() {
        val player = TrackPlayer()
        show(player, policy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true))
        openSheet(SUBTITLES, rightPresses = 1)
        choose("Size and timing")
        repeat(10) { choose("+0.1") }
        compose.onNodeWithText("+1.0 s").assertIsDisplayed()
        backInDialog()
        backInDialog()
        compose.runOnIdle { player.emitCue("English cue") }
        settle()
        compose.runOnIdle { player.advanceTo(2_000L) }
        settle()
        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertIsDisplayed()

        key(Key.DirectionCenter)
        choose("Deutsch")
        settle()
        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertDoesNotExist()
    }

    @Test
    fun theAccountsSubtitleSettingsDecideUntilTheViewerPicks() {
        val player = TrackPlayer()
        var policy by mutableStateOf<SubtitleStartupPolicy?>(
            SubtitleStartupPolicy(showSubtitles = false, autoSelectSubtitles = true),
        )
        show(player, policyProvider = { policy })
        compose.runOnIdle {
            assertTrue("hide_subtitles", C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
        }

        policy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false)
        settle()
        compose.runOnIdle {
            val parameters = player.trackSelectionParameters
            assertTrue(
                "dont_autoselect_subtitles keeps text on for forced tracks",
                C.TRACK_TYPE_TEXT !in parameters.disabledTrackTypes,
            )
            assertFalse(parameters.selectTextByDefault)
            assertNull(player.selectedTextIndex())
        }

        policy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true)
        settle()
        compose.runOnIdle {
            assertTrue(player.trackSelectionParameters.selectTextByDefault)
            assertEquals(0, player.selectedTextIndex())
        }

        openSheet(SUBTITLES, rightPresses = 1)
        choose("Off")
        settle()
        policy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false)
        settle()
        compose.runOnIdle { assertTrue(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) }
    }

    @Test
    fun automaticSubtitlesFollowTheAccountsDefaultTrackOverTheDeviceLanguage() {
        val player = TrackPlayer()
        compose.runOnUiThread {
            player.trackSelectionParameters =
                player.trackSelectionParameters.buildUpon().setPreferredTextLanguages("de").build()
        }
        show(player, policy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true))
        compose.runOnIdle { assertEquals(1, player.selectedTextIndex()) }

        val serverDefault = TrackGroup(
            "text-default",
            textFormat("t-en", "en", "English", C.SELECTION_FLAG_DEFAULT),
            textFormat("t-de", "de", "German"),
        )
        compose.runOnIdle { player.replaceTracks(audio = twoAudio(period = 2), text = serverDefault) }
        settle()
        compose.runOnIdle { assertEquals(0, player.selectedTextIndex()) }
    }

    @Test
    fun playbackResolvedForHiddenSubtitlesLeavesTheButtonOutBeforeTheSettingsLoad() {
        val player = TrackPlayer()
        show(player, readyState(subtitlesHidden = true), policy = null)

        chip(AUDIO).assertIsDisplayed()
        chip(SUBTITLES).assertDoesNotExist()
        compose.runOnIdle { assertNull(player.selectedTextIndex()) }
    }

    @Test
    fun hidingThatArrivesAfterAPickClosesThePickerAndTurnsSubtitlesOff() {
        val player = TrackPlayer()
        var policy by mutableStateOf<SubtitleStartupPolicy?>(null)
        show(player, policyProvider = { policy })
        openSheet(SUBTITLES, rightPresses = 1)
        choose("Deutsch")
        compose.runOnIdle { assertEquals(1, player.selectedTextIndex()) }
        key(Key.DirectionCenter)
        compose.onNode(hasText("Deutsch") and hasClickAction()).assertExists()

        policy = SubtitleStartupPolicy(showSubtitles = false, autoSelectSubtitles = true)
        settle()
        compose.onNode(hasText("Deutsch") and hasClickAction()).assertDoesNotExist()
        chip(SUBTITLES).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
            assertNull(player.selectedTextIndex())
        }
        compose.runOnIdle { player.replaceTracks(audio = twoAudio(period = 2), text = twoText(period = 2)) }
        settle()
        compose.runOnIdle { assertNull(player.selectedTextIndex()) }
    }

    @Test
    fun choicesSurviveARebuiltPlayerButSpeedStartsOverForTheNextFile() {
        val players = mutableListOf<TrackPlayer>()
        var state by mutableStateOf(readyState())
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PutioTvTheme {
                TvPlayerScreen(
                    state = state,
                    onBack = {},
                    onRetry = {},
                    onResume = {},
                    onRestart = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> TrackPlayer().also { players += it } },
                    subtitleStartupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true),
                )
            }
        }
        settle()
        openSheet(SPEED, rightPresses = 2)
        choose("2×")
        backInDialog()
        key(Key.DirectionLeft)
        key(Key.DirectionCenter)
        choose("Off")
        settle()

        restoration.emulateSavedInstanceStateRestore()
        settle()
        compose.runOnIdle {
            val rebuilt = players.last()
            assertEquals(2, players.size)
            assertEquals(2f, rebuilt.playbackParameters.speed)
            assertTrue(C.TRACK_TYPE_TEXT in rebuilt.trackSelectionParameters.disabledTrackTypes)
        }

        compose.runOnIdle { players.last().setPlaybackSpeed(1.5f) }
        settle()
        restoration.emulateSavedInstanceStateRestore()
        settle()
        compose.runOnIdle {
            assertEquals(3, players.size)
            assertEquals(1.5f, players.last().playbackParameters.speed)
        }

        state = readyState(fileId = 10L, name = "Next.mp4")
        settle()
        compose.runOnIdle {
            val next = players.last()
            assertEquals(4, players.size)
            assertEquals("Speed starts over with each file", 1f, next.playbackParameters.speed)
            assertEquals(0, next.selectedTextIndex())
        }
    }

    @Test
    fun theSeekBarStartsAtTheResumePositionBeforeTheStreamReportsItsDuration() {
        val player = TrackPlayer(durationKnown = false)
        show(player, readyState(resumePositionMillis = 420_000L, durationSeconds = 840.0))
        assertSeekBar(0.5f)
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("7:00")

        compose.runOnIdle { player.loadDuration(1_680_000L) }
        settle()
        assertSeekBar(0.25f)
    }

    private fun show(
        player: TrackPlayer,
        state: PlaybackState = readyState(),
        policy: SubtitleStartupPolicy? = null,
        policyProvider: () -> SubtitleStartupPolicy? = { policy },
        onBack: () -> Unit = {},
    ) {
        compose.mainClock.autoAdvance = false
        compose.setTvContent {
            TvPlayerScreen(
                state = state,
                onBack = onBack,
                onRetry = {},
                onResume = {},
                onRestart = {},
                onPlayerFailure = { _, _ -> },
                playerFactory = { _, _ -> player },
                subtitleStartupPolicy = policyProvider(),
            )
        }
        settle()
    }

    private fun openSheet(chip: String, rightPresses: Int = 0) {
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        repeat(rightPresses) { key(Key.DirectionRight) }
        chip(chip).assertIsSelected()
        key(Key.DirectionCenter)
    }

    private fun chip(prefix: String) = compose.onNodeWithContentDescription(prefix, substring = true)

    private fun describedAs(value: String) =
        SemanticsMatcher("described as $value") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true
        }

    private fun choose(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun key(key: Key) {
        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(key) }
        settle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun backInDialog() {
        compose.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            val down = AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_BACK)
            dialog.dispatchKeyEvent(down)
            dialog.dispatchKeyEvent(AndroidKeyEvent.changeAction(down, AndroidKeyEvent.ACTION_UP))
        }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
    }

    private fun assertSeekBar(fraction: Float) {
        compose.onNodeWithTag(TV_PLAYER_SEEK_BAR_TAG).assert(
            SemanticsMatcher("progress $fraction") {
                val info = it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
                info != null && abs(info.current - fraction) < 0.001f
            },
        )
    }

    private fun readyState(
        fileId: Long = 9L,
        name: String = "Sintel.mp4",
        resumePositionMillis: Long? = null,
        durationSeconds: Double? = null,
        subtitlesHidden: Boolean = false,
    ) = playbackState(
        target = PlaybackTarget(FilesItemId(fileId), name, PlaybackMediaType.VIDEO, durationSeconds),
        content = PlaybackContent.Ready(
            PlaybackSource(
                fileId = fileId,
                kind = PlaybackSourceKind.HLS,
                url = PutioCredentialUrl::class.java
                    .getDeclaredConstructor(String::class.java)
                    .newInstance("https://api.put.io/v2/files/$fileId/hls/media.m3u8?token=t"),
                startFromSeconds = 0.0,
                subtitles = PlaybackSubtitles.None,
            ),
            useStartFrom = false,
            subtitlesHidden = subtitlesHidden,
        ),
        nextRequestValue = 2L,
        resumePositionMillis = resumePositionMillis,
    )

    private companion object {
        const val SETTLE_MILLIS = 50L
        const val AUDIO = "Audio:"
        const val SUBTITLES = "Subtitles:"
        const val SPEED = "Playback speed"
    }
}

private fun audioFormat(id: String, language: String, label: String? = null) =
    Format.Builder().setId(id).setLanguage(language).setLabel(label).setSampleMimeType(MimeTypes.AUDIO_AAC).build()

private fun textFormat(id: String, language: String, label: String?, selectionFlags: Int = 0) =
    Format.Builder()
        .setId(id)
        .setLanguage(language)
        .setLabel(label)
        .setSampleMimeType(MimeTypes.TEXT_VTT)
        .setSelectionFlags(selectionFlags)
        .build()

private fun oneText() = TrackGroup("text-one", textFormat("t-en", "en", "English SDH"))

private fun twoAudio(period: Int = 1) =
    TrackGroup("audio-$period", audioFormat("a-it", "it"), audioFormat("a-en", "en"))

private fun twoText(period: Int = 1) =
    TrackGroup("text-$period", textFormat("t-en", "en", "English SDH"), textFormat("t-de", "de", null))

@UnstableApi
internal class TrackPlayer(
    private var audio: TrackGroup? = twoAudio(),
    private var text: TrackGroup? = twoText(),
    durationKnown: Boolean = true,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var durationUs = if (durationKnown) DURATION_US else C.TIME_UNSET
    private var state = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setVideoSize(VideoSize(1280, 720))
        .build()

    override fun getState(): State = state

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        state = state.buildUpon()
            .setPlaylist(mediaItems.map { item(it) })
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(startPositionMs)
            .build()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
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

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        state = state.buildUpon().setPlaybackParameters(playbackParameters).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(parameters: TrackSelectionParameters): ListenableFuture<*> {
        state = state.buildUpon().setTrackSelectionParameters(parameters).build()
        state = state.buildUpon().setPlaylist(state.playlist.map { refresh(it) }).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()

    fun replaceTracks(audio: TrackGroup?, text: TrackGroup?) {
        this.audio = audio
        this.text = text
        state = state.buildUpon().setPlaylist(state.playlist.map { refresh(it) }).build()
        invalidateState()
    }

    fun emitCue(value: String) {
        state = state.buildUpon().setCurrentCues(CueGroup(listOf(Cue.Builder().setText(value).build()), 0L)).build()
        invalidateState()
    }

    fun advanceTo(positionMillis: Long) {
        state = state.buildUpon().setContentPositionMs(positionMillis).build()
        invalidateState()
    }

    fun loadDuration(durationMillis: Long) {
        durationUs = durationMillis * 1_000L
        state = state.buildUpon().setPlaylist(state.playlist.map { refresh(it) }).build()
        invalidateState()
    }

    fun selectedAudioIndex(): Int? = selected(C.TRACK_TYPE_AUDIO)

    fun selectedTextIndex(): Int? = selected(C.TRACK_TYPE_TEXT)

    private fun selected(type: Int): Int? =
        currentTracks.groups.singleOrNull { it.type == type }?.let { group ->
            (0 until group.length).singleOrNull(group::isTrackSelected)
        }

    private fun item(mediaItem: MediaItem) =
        MediaItemData.Builder(mediaItem.mediaId)
            .setMediaItem(mediaItem)
            .setDurationUs(durationUs)
            .setIsSeekable(true)
            .setTracks(tracks())
            .build()

    private fun refresh(data: MediaItemData) = item(data.mediaItem)

    private fun tracks(): Tracks {
        val parameters = state.trackSelectionParameters
        val groups = buildList {
            audio?.let { group ->
                val chosen = parameters.overrides[group]?.trackIndices?.singleOrNull() ?: 0
                add(group(group, chosen))
            }
            text?.let { group ->
                val chosen = when {
                    C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes -> null
                    parameters.overrides[group] != null -> parameters.overrides[group]?.trackIndices?.singleOrNull()
                    parameters.selectTextByDefault -> group.automaticIndex(parameters)
                    else -> null
                }
                add(group(group, chosen))
            }
        }
        return Tracks(groups)
    }

    private fun TrackGroup.automaticIndex(parameters: TrackSelectionParameters): Int {
        val indices = 0 until length
        return parameters.preferredTextLanguages.firstNotNullOfOrNull { language ->
            indices.firstOrNull { getFormat(it).language == language }
        } ?: indices.firstOrNull { (getFormat(it).selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 } ?: 0
    }

    private fun group(group: TrackGroup, chosen: Int?) = Tracks.Group(
        group,
        false,
        IntArray(group.length) { C.FORMAT_HANDLED },
        BooleanArray(group.length) { it == chosen },
    )

    private companion object {
        const val DURATION_US = 14L * 60L * 1_000_000L
    }
}
