package io.putdotio.android.tv.player

import android.os.Looper
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.tv.material3.MaterialTheme
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SUBTITLE_CUES_TAG
import io.putdotio.android.playback.SubtitleStartupPolicy
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

/** Language, Subtitles and Speed on the TV player, over a fake Media3 player with tracks. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlayerOptionsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theButtonsFollowTheTracksAndSpeedIsAlwaysThere() {
        val player = TrackPlayer(audio = oneAudio(), text = null)
        show(player)
        compose.onNodeWithContentDescription(SPEED).assertIsDisplayed()
        compose.onNodeWithContentDescription(LANGUAGE).assertDoesNotExist()
        compose.onNodeWithContentDescription(SUBTITLES).assertDoesNotExist()

        compose.runOnIdle { player.replaceTracks(audio = twoAudio(period = 2), text = twoText(period = 2)) }
        settle()
        compose.onNodeWithContentDescription(LANGUAGE).assertIsDisplayed()
        compose.onNodeWithContentDescription(SUBTITLES).assertIsDisplayed()
        compose.onNodeWithContentDescription(SPEED).assertIsDisplayed()
    }

    @Test
    fun upReachesTheButtonsLeftAndRightWalkThemAndDownReturnsToScrubbing() {
        val player = TrackPlayer()
        show(player, resumePositionMillis = 60_000L)

        key(Key.DirectionUp)
        compose.onNodeWithContentDescription(LANGUAGE).assertIsSelected()
        key(Key.DirectionRight)
        compose.onNodeWithContentDescription(SUBTITLES).assertIsSelected()
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        compose.onNodeWithContentDescription(SPEED).assertIsSelected()
        compose.onNodeWithContentDescription(LANGUAGE).assertIsNotSelected()
        compose.runOnIdle { assertTrue("Walking the buttons never scrubs", player.playWhenReady) }

        key(Key.DirectionDown)
        compose.onNodeWithContentDescription(SPEED).assertIsNotSelected()
        key(Key.DirectionLeft)
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("00:45")
        compose.runOnIdle { assertFalse("Left on the seek bar scrubs", player.playWhenReady) }
    }

    @Test
    fun rewindOnAButtonPullsFocusBackToTheSeekBarAndScrubs() {
        val player = TrackPlayer()
        show(player, resumePositionMillis = 60_000L)
        key(Key.DirectionUp)
        compose.onNodeWithContentDescription(LANGUAGE).assertIsSelected()

        key(Key.MediaRewind)
        compose.onNodeWithContentDescription(LANGUAGE).assertIsNotSelected()
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("00:45")
        compose.runOnIdle { assertFalse(player.playWhenReady) }
    }

    @Test
    fun speedSetsThePlayerAndBackClosesThePickerBeforeTheControlsThenExits() {
        val player = TrackPlayer()
        var exits = 0
        show(player, onBack = { exits += 1 })
        openPicker(SPEED, rightPresses = 2)
        compose.onNodeWithText("Playback speed").assertIsDisplayed()
        choose("1.5×")
        settle()
        compose.onNodeWithText("Playback speed").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1.5f, player.playbackParameters.speed) }
        compose.onNodeWithContentDescription(SPEED).assertIsSelected().assert(stateIs("1.5×"))

        // Back with a picker open dismisses only the picker (#9): playback, speed and focus stay.
        key(Key.DirectionCenter)
        compose.onNodeWithText("Playback speed").assertIsDisplayed()
        backInDialog()
        compose.onNodeWithText("Playback speed").assertDoesNotExist()
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription(SPEED).assertIsSelected()
        compose.runOnIdle {
            assertEquals(0, exits)
            assertTrue(player.playWhenReady)
            assertEquals(1.5f, player.playbackParameters.speed)
        }
        // The picker's keys went back to the player: Left moves between the buttons again.
        key(Key.DirectionLeft)
        compose.onNodeWithContentDescription(SUBTITLES).assertIsSelected()

        back()
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, exits) }
        back()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test
    fun anOpenPickerKeepsTheControlsPastTheAutoHideDelay() {
        val player = TrackPlayer()
        show(player)
        openPicker(SPEED, rightPresses = 2)
        compose.mainClock.advanceTimeBy(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS * 2)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Playback speed").assertIsDisplayed()
    }

    @Test
    fun theChosenAudioTrackSurvivesATrackChange() {
        val player = TrackPlayer()
        show(player)
        openPicker(LANGUAGE)
        compose.onNodeWithText("Audio tracks").assertIsDisplayed()
        choose("English")
        settle()
        compose.runOnIdle { assertEquals(1, player.selectedAudioIndex()) }

        // A new period brings new track groups with the same formats; the choice follows them.
        compose.runOnIdle { player.replaceTracks(audio = twoAudio(period = 2), text = twoText(period = 2)) }
        settle()
        compose.runOnIdle { assertEquals(1, player.selectedAudioIndex()) }
    }

    /** #45: subtitles that were turned off kept showing on the shipped Android TV app. */
    @Test
    fun subtitlesOffStaysOffAndDrawsNothingAcrossSeeksAndTrackChanges() {
        val player = TrackPlayer()
        show(player, policy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true))
        compose.runOnIdle { player.emitCue("Automatic cue") }
        settle()
        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription(SUBTITLES).assert(stateIs("Subtitles on"))

        openPicker(SUBTITLES, rightPresses = 1)
        choose("Off")
        settle()
        // The renderer's last cues are still there; Off draws none of them.
        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription(SUBTITLES).assert(stateIs("Subtitles off"))

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

        // A picked track is found again in the next track list.
        key(Key.DirectionCenter)
        choose("German")
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
    fun theAccountsSubtitleSettingsDecideUntilTheViewerPicks() {
        val player = TrackPlayer()
        var policy by mutableStateOf<SubtitleStartupPolicy?>(SubtitleStartupPolicy(showSubtitles = false, autoSelectSubtitles = true))
        show(player, policyProvider = { policy })
        compose.runOnIdle {
            assertTrue("hide_subtitles", C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
        }

        policy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false)
        settle()
        compose.runOnIdle {
            val parameters = player.trackSelectionParameters
            assertTrue("dont_autoselect_subtitles keeps text on for forced tracks", C.TRACK_TYPE_TEXT !in parameters.disabledTrackTypes)
            assertFalse(parameters.selectTextByDefault)
            assertNull(player.selectedTextIndex())
        }

        policy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true)
        settle()
        compose.runOnIdle {
            assertTrue(player.trackSelectionParameters.selectTextByDefault)
            assertEquals(0, player.selectedTextIndex())
        }

        openPicker(SUBTITLES, rightPresses = 1)
        choose("Off")
        settle()
        // Settings that change after a pick no longer decide.
        policy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false)
        settle()
        compose.runOnIdle { assertTrue(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) }
    }

    @Test
    fun choicesSurviveARebuiltPlayerButSpeedStartsOverForTheNextFile() {
        val players = mutableListOf<TrackPlayer>()
        var state by mutableStateOf(readyState())
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
        openPicker(SPEED, rightPresses = 2)
        choose("2×")
        settle()
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

        state = readyState(fileId = 10L, name = "Next.mp4")
        settle()
        compose.runOnIdle {
            val next = players.last()
            assertEquals(3, players.size)
            assertEquals("Speed starts over with each file", 1f, next.playbackParameters.speed)
            assertEquals(0, next.selectedTextIndex())
        }
    }

    @Test
    fun theSeekBarStartsAtTheResumePositionBeforeTheStreamReportsItsDuration() {
        val player = TrackPlayer(durationKnown = false)
        show(player, resumePositionMillis = 420_000L, durationSeconds = 840.0)
        // No poll has run yet; the listing's duration places the bar where playback starts.
        assertSeekBar(0.5f)
        compose.onNodeWithTag(TV_PLAYER_ELAPSED_TAG).assertTextEquals("07:00")

        // The stream's own duration replaces it as soon as the timeline arrives, not at the next poll.
        compose.runOnIdle { player.loadDuration(1_680_000L) }
        settle()
        assertSeekBar(0.25f)
    }

    private fun show(
        player: TrackPlayer,
        resumePositionMillis: Long? = null,
        durationSeconds: Double? = null,
        policy: SubtitleStartupPolicy? = null,
        policyProvider: () -> SubtitleStartupPolicy? = { policy },
        onBack: () -> Unit = {},
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlayerScreen(
                    state = readyState(resumePositionMillis = resumePositionMillis, durationSeconds = durationSeconds),
                    onBack = onBack,
                    onRetry = {},
                    onResume = {},
                    onRestart = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> player },
                    subtitleStartupPolicy = policyProvider(),
                )
            }
        }
        settle()
    }

    private fun openPicker(button: String, rightPresses: Int = 0) {
        key(Key.DirectionUp)
        repeat(rightPresses) { key(Key.DirectionRight) }
        compose.onNodeWithContentDescription(button).assertIsSelected()
        key(Key.DirectionCenter)
    }

    /** Center on a picker row: the dialog's rows are TV list items that act on the select key. */
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

    /** Back reaches the picker's own window, as the remote's does. */
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

    private fun stateIs(value: String) =
        SemanticsMatcher("state $value") { it.config.getOrNull(SemanticsProperties.StateDescription) == value }

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
    ) = PlaybackState(
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
        ),
        nextRequestValue = 2L,
        resumePositionMillis = resumePositionMillis,
    )

    private companion object {
        const val SETTLE_MILLIS = 50L
        const val LANGUAGE = "Language"
        const val SUBTITLES = "Subtitles"
        const val SPEED = "Speed"
    }
}

private fun audioFormat(id: String, language: String) =
    Format.Builder().setId(id).setLanguage(language).setSampleMimeType(MimeTypes.AUDIO_AAC).build()

private fun textFormat(id: String, language: String, label: String?) =
    Format.Builder().setId(id).setLanguage(language).setLabel(label).setSampleMimeType(MimeTypes.TEXT_VTT).build()

private fun oneAudio() = TrackGroup(audioFormat("a-it", "it"))

/** Each [period] gets distinct track groups carrying the same formats, as a new HLS period does. */
private fun twoAudio(period: Int = 1) = TrackGroup("audio-$period", audioFormat("a-it", "it"), audioFormat("a-en", "en"))

private fun twoText(period: Int = 1) =
    TrackGroup("text-$period", textFormat("t-en", "en", "English SDH"), textFormat("t-de", "de", null))

/** Selects tracks from the parameters roughly as ExoPlayer's default selector would. */
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
                    parameters.selectTextByDefault -> 0
                    else -> null
                }
                add(group(group, chosen))
            }
        }
        return Tracks(groups)
    }

    private fun group(group: TrackGroup, chosen: Int?) =
        Tracks.Group(group, false, IntArray(group.length) { C.FORMAT_HANDLED }, BooleanArray(group.length) { it == chosen })

    private companion object {
        const val DURATION_US = 14L * 60L * 1_000_000L
    }
}
