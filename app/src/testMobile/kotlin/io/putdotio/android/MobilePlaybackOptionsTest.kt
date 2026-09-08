package io.putdotio.android

import android.os.Bundle
import android.os.Looper
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
@UnstableApi
class MobilePlaybackOptionsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun directSpeedButtonOpensChoicesAndUpdatesItsCurrentValue() {
        val player = OptionsPlayer()
        compose.setContent {
            PutioTheme {
                Row { MobilePlaybackOptions(player, {}, {}, {}, {}, directControls = true) }
            }
        }

        compose.onNodeWithContentDescription("Playback options").assertDoesNotExist()
        compose.onNodeWithText("1×").performClick()
        compose.onNode(hasText("1×") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("1.5×").assertIsNotSelected()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithText("1.5×").performClick()
        compose.onNodeWithText("1.5×").assertIsDisplayed()
        compose.onNodeWithContentDescription("Playback speed 1.5×").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1.5f, player.playbackParameters.speed) }
    }

    @Test
    fun directAudioButtonOpensTrackChoicesWithoutRootSettings() {
        val player = OptionsPlayer()
        var selected: AudioSelection = AudioSelection.Automatic
        compose.setContent {
            PutioTheme {
                Row { MobilePlaybackOptions(player, { selected = it }, {}, {}, {}, directControls = true) }
            }
        }

        compose.onNodeWithText("Audio").performClick()
        compose.onNodeWithText("Automatic").assertIsSelected()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithText("Deutsch").performClick()
        compose.runOnIdle {
            assertEquals(AudioSelection.Track(player.audio.getFormat(1).toAudioTrackIdentity()), selected)
            assertEquals(listOf(1), player.trackSelectionParameters.overrides.getValue(player.audio).trackIndices)
            assertEquals(player.subtitleOverride, player.trackSelectionParameters.overrides[player.text])
        }
    }

    @Test
    fun speedMenuChangesPlayerAndExposesSelectedSpeed() {
        val player = OptionsPlayer()
        val visibility = mutableListOf<Boolean>()
        compose.setContent {
            PutioTheme {
                MobilePlaybackOptions(player, {}, { visibility += it }, {}, {})
            }
        }

        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("Playback speed").performClick()
        compose.onNodeWithText("1×").assertIsSelected()
        compose.onNodeWithText("1.5×").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(1.5f, player.playbackParameters.speed) }
        assertEquals(listOf(true, false), visibility)
        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("Playback speed").performClick()
        compose.onNodeWithText("1.5×").assertIsSelected()
        compose.onNodeWithText("1×").assertIsNotSelected()
    }

    @Test
    fun audioMenuSelectsTrackAndAutomaticWithoutChangingSubtitles() {
        val player = OptionsPlayer()
        val selections = mutableListOf<AudioSelection>()
        compose.setContent {
            PutioTheme {
                MobilePlaybackOptions(player, { selections += it }, {}, {}, {})
            }
        }

        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("Audio track").performClick()
        compose.onNodeWithText("Automatic").assertIsSelected()
        compose.onNodeWithText("English").assertIsNotSelected()
        compose.onNodeWithText("Deutsch").assertIsNotSelected().performClick()
        compose.runOnIdle {
            assertEquals(listOf(1), player.trackSelectionParameters.overrides.getValue(player.audio).trackIndices)
            assertEquals(player.subtitleOverride, player.trackSelectionParameters.overrides[player.text])
            assertEquals(AudioSelection.Track(player.audio.getFormat(1).toAudioTrackIdentity()), selections.single())
        }
        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("Audio track").performClick()
        compose.onNodeWithText("Deutsch").assertIsSelected()
        compose.onNodeWithText("English").assertIsNotSelected()
        compose.onNodeWithText("Automatic").assertIsNotSelected().performClick()
        compose.runOnIdle {
            assertEquals(AudioSelection.Automatic, selections.last())
            assertEquals(mapOf(player.text to player.subtitleOverride), player.trackSelectionParameters.overrides)
        }
    }

    @Test
    fun duplicateAudioLabelsRemainDistinctInChoicesAndSummary() {
        assertDuplicateAudioChoices(useLanguage = false)
    }

    @Test
    fun duplicateAudioLanguagesRemainDistinctInChoicesAndSummary() {
        assertDuplicateAudioChoices(useLanguage = true)
    }

    private fun assertDuplicateAudioChoices(useLanguage: Boolean) {
        val label = if (useLanguage) "en" else "English"
        fun audioFormat(id: String, displayLabel: String? = if (useLanguage) null else label): Format = Format.Builder()
            .setId(id)
            .setLanguage("en")
            .setLabel(displayLabel)
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .build()
        val first = audioFormat("first")
        val second = audioFormat("second")
        val third = audioFormat("third", "$label (track 1)")
        val player = OptionsPlayer(audioGroup = TrackGroup(first, second, third))
        var selection: AudioSelection = AudioSelection.Automatic
        compose.setContent {
            PutioTheme { MobilePlaybackOptions(player, { selection = it }, {}, {}, {}) }
        }

        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("Audio track").performClick()
        compose.onNodeWithText("$label (track 1)").assertIsNotSelected()
        compose.onNodeWithText("$label (track 1) (track 3)").assertIsNotSelected()
        compose.onNodeWithText("$label (track 2)").assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("$label (track 2)").assertIsDisplayed()
        compose.onNodeWithText("Audio track").performClick()
        compose.onNodeWithText("$label (track 2)").assertIsSelected()
        compose.onNodeWithText("$label (track 1)").assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(AudioSelection.Track(second.toAudioTrackIdentity()), selection)
            assertEquals(player.subtitleOverride, player.trackSelectionParameters.overrides[player.text])
            val recreated = TrackGroup(second, first)
            val retained = requireNotNull(selection.toBundle().toAudioSelection())
            val restored = player.trackSelectionParameters.withAudioSelection(
                retained,
                listOf(
                    MobileAudioTrack(recreated, 0, label = label, selected = false),
                    MobileAudioTrack(recreated, 1, label = label, selected = false),
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
            PutioTheme { MobilePlaybackOptions(player, { selection = it }, {}, {}, {}) }
        }

        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("Audio track").performClick()
        compose.onNodeWithText("English (track 2)").performClick()
        compose.runOnIdle {
            player.trackSelectionParameters = player.trackSelectionParameters.withRetainedAudioSelection(
                selection,
                player.currentTracks.mobileAudioTracks(),
            )
            assertEquals(listOf(1), player.trackSelectionParameters.overrides.getValue(player.audio).trackIndices)
            assertEquals(player.subtitleOverride, player.trackSelectionParameters.overrides[player.text])
        }
        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("English (track 2)").assertIsDisplayed()
        compose.onNodeWithText("Audio track").performClick()
        compose.onNodeWithText("English (track 2)").assertIsSelected()
        compose.onNodeWithText("English (track 1)").assertIsNotSelected()
        compose.onNodeWithText("English (track 1)").performClick()
        compose.runOnIdle {
            assertEquals(listOf(0), player.trackSelectionParameters.overrides.getValue(player.audio).trackIndices)
            assertEquals(player.subtitleOverride, player.trackSelectionParameters.overrides[player.text])
        }
    }

    @Test
    fun unavailableCommandsDisableSpeedAndAudioChoices() {
        val player = OptionsPlayer(canChangeOptions = false)
        compose.setContent {
            PutioTheme { MobilePlaybackOptions(player, {}, {}, {}, {}) }
        }

        compose.onNodeWithContentDescription("Playback options").performClick()
        listOf("Playback speed", "Audio track").forEach { label ->
            compose.onNodeWithText(label).assertIsNotEnabled()
        }
    }

    @Test
    fun singleAudioTrackOffersSpeedWithoutAnAudioChooser() {
        val player = OptionsPlayer(singleAudioTrack = true)
        compose.setContent {
            PutioTheme { MobilePlaybackOptions(player, {}, {}, {}, {}) }
        }

        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("Playback speed").assertIsEnabled()
        compose.onNodeWithText("Automatic").assertDoesNotExist()
        compose.onNodeWithText("English").assertDoesNotExist()
        compose.onNodeWithText("Deutsch").assertDoesNotExist()
        compose.onNodeWithText("Audio track").assertDoesNotExist()
    }

    @Test
    fun subpageBackReturnsToSettingsWithoutChangingPlayback() {
        val player = OptionsPlayer()
        val visibility = mutableListOf<Boolean>()
        compose.setContent {
            PutioTheme { MobilePlaybackOptions(player, {}, { visibility += it }, {}, {}) }
        }

        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("Playback speed").performClick()
        compose.onNodeWithText("1.5×").assertIsNotSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Audio track").assertIsEnabled()
        compose.onNodeWithText("1.5×").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1f, player.playbackParameters.speed)
            assertEquals(listOf(true), visibility)
        }
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
                    label = "1.25×",
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
                player.currentTracks.mobileAudioTracks(),
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
                MobileAudioTrack(player.audio, 1, label = "English", selected = false),
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
                player.currentTracks.mobileAudioTracks(),
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
