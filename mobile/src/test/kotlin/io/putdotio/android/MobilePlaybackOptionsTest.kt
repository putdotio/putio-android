package io.putdotio.android

import android.os.Bundle
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player as Media3Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.design.PutioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.putdotio.android.playback.SubtitleSelection
import io.putdotio.android.playback.AudioSelection
import io.putdotio.android.playback.PlaybackAudioTrack
import io.putdotio.android.playback.playbackAudioTracks
import io.putdotio.android.playback.toAudioTrackIdentity
import io.putdotio.android.playback.withAudioSelection
import io.putdotio.android.playback.withAudioTrack
import io.putdotio.android.playback.withRetainedAudioSelection
import io.putdotio.android.playback.toAudioSelection
import io.putdotio.android.playback.toBundle
import io.putdotio.android.playback.MobileAudioChip
import io.putdotio.android.playback.MobileAudioSheetContent
import io.putdotio.android.playback.MOBILE_AUDIO_NEXT_TAG
import io.putdotio.android.playback.MOBILE_AUDIO_PREVIOUS_TAG
import io.putdotio.android.playback.MOBILE_NOW_PLAYING_SHEET_TAG
import io.putdotio.android.playback.MOBILE_UP_NEXT_CHIP_TAG
import io.putdotio.android.playback.MobileNowPlayingScreen
import io.putdotio.android.playback.MobileSleepTimer
import io.putdotio.android.playback.PlaybackMediaType.AUDIO
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.MobileSpeedChip
import io.putdotio.android.playback.MobileSpeedSheetContent
import io.putdotio.android.playback.rememberPlayerOptionsSnapshot
import io.putdotio.android.playback.PlaybackOption
import io.putdotio.android.playback.RetainedPlayerPreferences
import io.putdotio.android.playback.adoptPlaybackOptions
import io.putdotio.android.playback.observePlayerControlKeyActivity
import io.putdotio.android.playback.rememberRetainedPlayerPreferences
import io.putdotio.android.playback.toRetainedPlayerPreferences

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
@UnstableApi
class MobilePlaybackOptionsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun videoSpeedSheetStepsThroughThePresetsAndItsChipFollows() {
        val player = OptionsPlayer()
        compose.setContent {
            PutioTheme {
                val options = rememberPlayerOptionsSnapshot(player)
                Column {
                    MobileSpeedChip(options, {})
                    MobileSpeedSheetContent(player, options)
                }
            }
        }

        compose.onNodeWithContentDescription("Playback speed 1×").assertIsDisplayed()
        compose.onNodeWithContentDescription("Faster, 1.25×").performClick()
        compose.runOnIdle { assertEquals(1.25f, player.playbackParameters.speed) }
        compose.onNodeWithContentDescription("Playback speed 1.25×").assertIsDisplayed()
        compose.onNode(hasText("2×") and isSelectable()).assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(2f, player.playbackParameters.speed) }
        compose.onNode(hasText("2×") and isSelectable()).assertIsSelected()
        listOf(1.5f, 1.25f, 1f, 0.75f, 0.5f).forEach { expected ->
            compose.onNodeWithContentDescription("Slower", substring = true).performClick()
            compose.runOnIdle { assertEquals(expected, player.playbackParameters.speed) }
        }
        compose.onNodeWithContentDescription("Slower", substring = true).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Playback speed", substring = false)
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(7f)) }
        compose.runOnIdle { assertEquals(3f, player.playbackParameters.speed) }
        compose.onNodeWithContentDescription("Faster", substring = true).assertIsNotEnabled()
    }

    @Test
    fun videoAudioSheetListsTracksAndPicksOneWithoutChangingSubtitles() {
        val player = OptionsPlayer()
        val selections = mutableListOf<AudioSelection>()
        var done = 0
        compose.setContent {
            PutioTheme {
                val options = rememberPlayerOptionsSnapshot(player)
                Column {
                    MobileAudioChip(options, {})
                    MobileAudioSheetContent(player, options, { selections += it }, { done += 1 })
                }
            }
        }

        compose.onNodeWithContentDescription("Audio: English").assertIsDisplayed()
        compose.onNode(hasText("English") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Deutsch") and isSelectable()).assertIsNotSelected().performClick()
        compose.runOnIdle {
            assertEquals(listOf(1), player.trackSelectionParameters.overrides.getValue(player.audio).trackIndices)
            assertEquals(player.subtitleOverride, player.trackSelectionParameters.overrides[player.text])
            assertEquals(AudioSelection.Track(player.audio.getFormat(1).toAudioTrackIdentity()), selections.single())
            assertEquals(1, done)
        }
        compose.onNode(hasText("Deutsch") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Audio: Deutsch").assertIsDisplayed()
    }

    @Test
    fun nowPlayingSpeedChipOpensTheSameSpeedPresetsAsVideo() {
        val player = OptionsPlayer()
        compose.setContent { PutioTheme { NowPlaying(player) } }

        compose.onNodeWithContentDescription("Playback speed 1×").performScrollTo().performClick()
        compose.onNodeWithTag(MOBILE_NOW_PLAYING_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Faster, 1.25×").performClick()
        compose.runOnIdle { assertEquals(1.25f, player.playbackParameters.speed) }
        compose.onNodeWithContentDescription("Playback speed", substring = false)
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(7f)) }
        compose.runOnIdle { assertEquals(3f, player.playbackParameters.speed) }
        compose.onNodeWithContentDescription("Playback speed 3×").assertExists()
    }

    @Test
    fun nowPlayingOffersAudioTracksUnderMoreOnlyWhenThereIsAChoice() {
        var player by mutableStateOf(OptionsPlayer(singleAudioTrack = true))
        val selections = mutableListOf<AudioSelection>()
        compose.setContent { PutioTheme { NowPlaying(player, onAudioSelectionChanged = { selections += it }) } }

        compose.onNodeWithContentDescription("More options").assertDoesNotExist()
        compose.onNodeWithContentDescription("Playback speed 1×").performScrollTo().assertIsEnabled()
        val twoTracks = OptionsPlayer()
        compose.runOnIdle { player = twoTracks }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Audio track").performClick()
        compose.onNode(hasText("Deutsch") and isSelectable()).assertIsNotSelected().performClick()
        compose.runOnIdle {
            assertEquals(listOf(1), twoTracks.trackSelectionParameters.overrides.getValue(twoTracks.audio).trackIndices)
            assertEquals(twoTracks.subtitleOverride, twoTracks.trackSelectionParameters.overrides[twoTracks.text])
            assertEquals(
                AudioSelection.Track(twoTracks.audio.getFormat(1).toAudioTrackIdentity()),
                selections.single(),
            )
        }
        compose.onAllNodesWithTag(MOBILE_NOW_PLAYING_SHEET_TAG).assertCountEquals(0)
    }

    @Test
    fun sleepTimerChipStartsShowsAndCancelsTheTimer() {
        val timer = MobileSleepTimer(clock = { 0L })
        compose.setContent { PutioTheme { NowPlaying(OptionsPlayer(), sleepTimer = timer) } }

        compose.onNodeWithText("Sleep timer").performScrollTo().performClick()
        compose.onNode(hasText("Off") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("30 minutes") and isSelectable()).assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(30, timer.run.value?.minutes) }
        compose.onNodeWithContentDescription("Sleep timer, 30:00 left").performScrollTo().performClick()
        compose.onNode(hasText("30 minutes") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("1 hour") and isSelectable()).assertIsNotSelected()
        compose.onNode(hasText("Off") and isSelectable()).performClick()
        compose.runOnIdle { assertNull(timer.run.value) }
        compose.onNodeWithText("Sleep timer").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun skipButtonsAndUpNextPlayTheFilesAroundThisOneInItsFolder() {
        val previous = PlaybackTarget(FilesItemId(40L), "FFTT Full Report October 20, 2026.mp3", AUDIO)
        val next = PlaybackTarget(FilesItemId(43L), "FFTT Tree Rings October 30, 2026.mp3", AUDIO)
        var neighbours by mutableStateOf(PlaybackNeighbours())
        val played = mutableListOf<PlaybackTarget>()
        compose.setContent { PutioTheme { NowPlaying(OptionsPlayer(), neighbours, onPlay = { played += it }) } }

        compose.onNodeWithTag(MOBILE_AUDIO_PREVIOUS_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_AUDIO_NEXT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_UP_NEXT_CHIP_TAG).assertIsNotEnabled()
        compose.runOnIdle { neighbours = PlaybackNeighbours(previous, next, folderName = "FFTT") }
        compose.onNodeWithText("FFTT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_AUDIO_PREVIOUS_TAG).assertContentDescriptionEquals("Previous file")
            .performScrollTo().performClick()
        compose.onNodeWithTag(MOBILE_AUDIO_NEXT_TAG).assertContentDescriptionEquals("Next file")
            .performScrollTo().performClick()
        compose.onNodeWithTag(MOBILE_UP_NEXT_CHIP_TAG).performScrollTo().performClick()
        compose.onNodeWithText(next.name).performClick()
        compose.runOnIdle { assertEquals(listOf(previous, next, next), played) }
        compose.onAllNodesWithTag(MOBILE_NOW_PLAYING_SHEET_TAG).assertCountEquals(0)
    }

    @Test
    fun aNumberedTrackNeverMeetsOneAlreadyLabelledThatWay() {
        val format = { id: String, label: String ->
            Format.Builder().setId(id).setLabel(label).setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        }
        val player = OptionsPlayer(
            audioGroup = TrackGroup(format("a", "English"), format("b", "English"), format("c", "English (track 1)")),
        )
        compose.setContent {
            PutioTheme {
                val options = rememberPlayerOptionsSnapshot(player)
                MobileAudioSheetContent(player, options, {}, {})
            }
        }

        listOf("English (track 1)", "English (track 2)", "English (track 1) (track 3)").forEach { label ->
            compose.onNode(hasText(label) and isSelectable()).assertExists()
        }
    }

    @Test
    fun duplicateTracksStayDistinctAndTheChoiceSurvivesRecreation() {
        val format = { id: String ->
            Format.Builder().setId(id).setLanguage("en").setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        }
        val first = format("first")
        val second = format("second")
        val player = OptionsPlayer(audioGroup = TrackGroup(first, second, format("third")))
        var selection: AudioSelection = AudioSelection.Automatic
        compose.setContent {
            PutioTheme {
                val options = rememberPlayerOptionsSnapshot(player)
                MobileAudioSheetContent(player, options, { selection = it }, {})
            }
        }

        compose.onNode(hasText("English (track 1)") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("English (track 3)") and isSelectable()).assertIsNotSelected()
        compose.onNode(hasText("English (track 2)") and isSelectable()).assertIsNotSelected().performClick()
        compose.onNode(hasText("English (track 2)") and isSelectable()).assertIsSelected()
        compose.runOnIdle {
            assertEquals(AudioSelection.Track(second.toAudioTrackIdentity()), selection)
            assertEquals(player.subtitleOverride, player.trackSelectionParameters.overrides[player.text])
            val recreated = TrackGroup(second, first)
            val retained = requireNotNull(selection.toBundle().toAudioSelection())
            val restored = player.trackSelectionParameters.withAudioSelection(
                retained,
                listOf(
                    PlaybackAudioTrack(recreated, 0, label = null, selected = false),
                    PlaybackAudioTrack(recreated, 1, label = null, selected = false),
                ),
            )
            assertEquals(listOf(0), restored.overrides.getValue(recreated).trackIndices)
            assertEquals(player.subtitleOverride, restored.overrides[player.text])
        }
    }

    @Test
    fun concreteAudioChoiceWorksWhenAllFormatMetadataMatches() {
        val format = Format.Builder()
            .setId("duplicate")
            .setLabel("English")
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .build()
        val player = OptionsPlayer(audioGroup = TrackGroup(format, format))
        var selection: AudioSelection = AudioSelection.Automatic
        compose.setContent {
            PutioTheme {
                val options = rememberPlayerOptionsSnapshot(player)
                MobileAudioSheetContent(player, options, { selection = it }, {})
            }
        }

        compose.onNodeWithText("English (track 2)").performClick()
        compose.runOnIdle {
            player.trackSelectionParameters = player.trackSelectionParameters.withRetainedAudioSelection(
                selection,
                player.currentTracks.playbackAudioTracks(),
            )
            assertEquals(listOf(1), player.trackSelectionParameters.overrides.getValue(player.audio).trackIndices)
            assertEquals(player.subtitleOverride, player.trackSelectionParameters.overrides[player.text])
        }
        compose.onNode(hasText("English (track 2)") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("English (track 1)") and isSelectable()).assertIsNotSelected().performClick()
        compose.runOnIdle {
            assertEquals(listOf(0), player.trackSelectionParameters.overrides.getValue(player.audio).trackIndices)
            assertEquals(player.subtitleOverride, player.trackSelectionParameters.overrides[player.text])
        }
    }

    @Test
    fun unavailableCommandsDisableSpeedAndAudioChoices() {
        val player = OptionsPlayer(canChangeOptions = false)
        compose.setContent {
            PutioTheme {
                val options = rememberPlayerOptionsSnapshot(player)
                Column {
                    MobileSpeedSheetContent(player, options)
                    MobileAudioSheetContent(player, options, {}, {})
                }
            }
        }

        compose.onNodeWithContentDescription("Faster", substring = true).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Slower", substring = true).assertIsNotEnabled()
        listOf("English", "Deutsch").forEach { label ->
            compose.onNode(hasText(label) and isSelectable()).assertIsNotEnabled()
        }
    }

    @Composable
    private fun NowPlaying(
        player: Media3Player,
        neighbours: PlaybackNeighbours = PlaybackNeighbours(),
        onPlay: (PlaybackTarget) -> Unit = {},
        onAudioSelectionChanged: (AudioSelection) -> Unit = {},
        sleepTimer: MobileSleepTimer = MobileSleepTimer(clock = { 0L }),
    ) {
        MobileNowPlayingScreen(
            player = player,
            title = "song.mp3",
            neighbours = neighbours,
            seekEnabled = true,
            onSeek = {},
            onScrub = {},
            onPlay = onPlay,
            onAudioSelectionChanged = onAudioSelectionChanged,
            sleepTimer = sleepTimer,
        )
    }

    @Test
    fun keyboardActivatesPlaybackOptionAndReportsNavigation() {
        val player = OptionsPlayer()
        var keyboardEvents = 0
        lateinit var inputMode: InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            PutioTheme {
                PlaybackOption(
                    label = AnnotatedString("1.25×"),
                    selected = false,
                    enabled = true,
                    onClick = { player.setPlaybackSpeed(1.25f) },
                    modifier = Modifier.observePlayerControlKeyActivity { keyboardEvents += 1 },
                )
            }
        }

        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithText("1.25×")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
        compose.waitForIdle()
        compose.onNodeWithText("1.25×").performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        compose.runOnIdle {
            assertEquals(1.25f, player.playbackParameters.speed)
            assertTrue(keyboardEvents > 0)
        }
    }

    @Test
    fun savedPreferencesRestoreSpeedAndAudioAlongsideExistingPlaybackState() {
        val restoration = StateRestorationTester(compose)
        lateinit var preferences: RetainedPlayerPreferences
        val player = OptionsPlayer()
        val audio = AudioSelection.Track(player.audio.getFormat(1).toAudioTrackIdentity())
        restoration.setContent { preferences = rememberRetainedPlayerPreferences(42L) }
        compose.runOnIdle {
            preferences.playbackSpeed = 1.5f
            preferences.audioSelection = audio
            preferences.subtitleSelection = SubtitleSelection.Off
            preferences.positionMillis = 1234L
            preferences.sessionHandled = true
        }
        val previous = preferences
        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle {
            assertNotSame(previous, preferences)
            assertEquals(1.5f, preferences.playbackSpeed)
            assertEquals(audio, preferences.audioSelection)
            assertEquals(SubtitleSelection.Off, preferences.subtitleSelection)
            assertEquals(1234L, preferences.positionMillis)
            assertTrue(preferences.sessionHandled)
        }
    }

    @Test
    fun liveSessionOptionsReplaceRouteDefaultsWithoutWritingToPlayer() {
        val player = OptionsPlayer()
        val preferences = RetainedPlayerPreferences(subtitleSelection = SubtitleSelection.Off)
        compose.runOnIdle {
            player.setPlaybackSpeed(2f)
            val selected = AudioSelection.Track(player.audio.getFormat(1).toAudioTrackIdentity())
            player.trackSelectionParameters = player.trackSelectionParameters.withAudioSelection(
                selected,
                player.currentTracks.playbackAudioTracks(),
            )
            val originalParameters = player.trackSelectionParameters

            preferences.adoptPlaybackOptions(player)

            assertEquals(2f, preferences.playbackSpeed)
            assertEquals(selected, preferences.audioSelection)
            assertEquals(SubtitleSelection.Off, preferences.subtitleSelection)
            assertEquals(originalParameters, player.trackSelectionParameters)
            player.trackSelectionParameters = originalParameters.withAudioSelection(
                AudioSelection.Automatic,
                emptyList(),
            )
            preferences.adoptPlaybackOptions(player)
            assertEquals(AudioSelection.Automatic, preferences.audioSelection)
        }
    }

    @Test
    fun adoptionWhileTracksAreUnavailablePreservesTheConcreteAudioOverride() {
        val format = Format.Builder()
            .setId("duplicate")
            .setLabel("English")
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .build()
        val player = OptionsPlayer(audioGroup = TrackGroup(format, format))
        val preferences = RetainedPlayerPreferences()
        compose.runOnIdle {
            player.setPlaybackSpeed(1.5f)
            player.trackSelectionParameters = player.trackSelectionParameters.withAudioTrack(
                PlaybackAudioTrack(player.audio, 1, label = "English", selected = false),
            )
            val selectedTracks = player.currentTracks
            val originalParameters = player.trackSelectionParameters
            player.updateTracks(Tracks.EMPTY)

            preferences.adoptPlaybackOptions(player)

            assertEquals(AudioSelection.Track(format.toAudioTrackIdentity()), preferences.audioSelection)
            assertEquals(1.5f, preferences.playbackSpeed)
            assertEquals(originalParameters, player.trackSelectionParameters)
            assertTrue(player.currentTracks.groups.isEmpty())
            player.trackSelectionParameters = player.trackSelectionParameters.withRetainedAudioSelection(
                preferences.audioSelection,
                emptyList(),
            )
            assertEquals(originalParameters, player.trackSelectionParameters)
            player.updateTracks(Tracks.EMPTY)

            player.updateTracks(selectedTracks)
            val restored = player.trackSelectionParameters.withRetainedAudioSelection(
                preferences.audioSelection,
                player.currentTracks.playbackAudioTracks(),
            )
            assertEquals(listOf(1), restored.overrides.getValue(player.audio).trackIndices)
            assertEquals(player.subtitleOverride, restored.overrides[player.text])
            assertEquals(originalParameters, restored)
        }
    }

    @Test
    fun oldOrInvalidSavedSpeedFallsBackToNormal() {
        listOf(Bundle(), Bundle().apply { putFloat("playbackSpeed", Float.NaN) }).forEach { bundle ->
            val preferences = bundle.toRetainedPlayerPreferences()
            assertEquals(1f, preferences.playbackSpeed)
            assertEquals(AudioSelection.Automatic, preferences.audioSelection)
        }
    }
}

@UnstableApi
private class OptionsPlayer(
    canChangeOptions: Boolean = true,
    singleAudioTrack: Boolean = false,
    audioGroup: TrackGroup? = null,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    val audio = audioGroup ?: if (singleAudioTrack) {
        TrackGroup(Format.Builder().setId("en").setLabel("English").setSampleMimeType(MimeTypes.AUDIO_AAC).build())
    } else TrackGroup(
        Format.Builder().setId("en").setLabel("English").setSampleMimeType(MimeTypes.AUDIO_AAC).build(),
        Format.Builder().setId("de").setLabel("Deutsch").setSampleMimeType(MimeTypes.AUDIO_AAC).build(),
    )
    val text = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build())
    val subtitleOverride = TrackSelectionOverride(text, 0)
    private var state = State.Builder()
        .setAvailableCommands(
            Media3Player.Commands.Builder().addAllCommands().apply {
                if (!canChangeOptions) {
                    remove(Media3Player.COMMAND_SET_SPEED_AND_PITCH)
                    remove(Media3Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
                }
            }.build(),
        )
        .setTrackSelectionParameters(
            TrackSelectionParameters.Builder().setOverrideForType(subtitleOverride).build(),
        )
        .setPlaylist(
            listOf(
                MediaItemData.Builder("test-audio")
                    .setTracks(audioTracks(selectedIndex = 0))
                    .build(),
            ),
        )
        .setCurrentMediaItemIndex(0)
        .build()

    override fun getState(): State = state

    fun updateTracks(tracks: Tracks) {
        state = state.buildUpon()
            .setPlaylist(state.playlist.map { it.buildUpon().setTracks(tracks).build() })
            .build()
        invalidateState()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        state = state.buildUpon().setPlaybackParameters(playbackParameters).build()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(parameters: TrackSelectionParameters): ListenableFuture<*> {
        val selectedIndex = parameters.overrides[audio]?.trackIndices?.singleOrNull() ?: 0
        state = state.buildUpon()
            .setTrackSelectionParameters(parameters)
            .setPlaylist(state.playlist.map { it.buildUpon().setTracks(audioTracks(selectedIndex)).build() })
            .build()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    private fun audioTracks(selectedIndex: Int): Tracks =
        Tracks(
            listOf(
                Tracks.Group(
                    audio,
                    false,
                    IntArray(audio.length) { C.FORMAT_HANDLED },
                    BooleanArray(audio.length) { it == selectedIndex },
                ),
            ),
        )
}
