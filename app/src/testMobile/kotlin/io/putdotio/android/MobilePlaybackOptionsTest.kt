package io.putdotio.android

import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
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
    fun speedMenuChangesPlayerAndExposesSelectedSpeed() {
        val player = OptionsPlayer()
        val visibility = mutableListOf<Boolean>()
        compose.setContent {
            PutioTheme {
                MobilePlaybackOptions(player, {}, { visibility += it }, {}, {})
            }
        }

        compose.onNodeWithText("Playback options").performClick()
        compose.onNodeWithText("1×").assertIsOn()
        compose.onNodeWithText("1.5×").assertIsOff().performClick()
        compose.runOnIdle { assertEquals(1.5f, player.playbackParameters.speed) }
        assertEquals(listOf(true, false), visibility)
        compose.onNodeWithText("Playback options").performClick()
        compose.onNodeWithText("1.5×").assertIsOn()
        compose.onNodeWithText("1×").assertIsOff()
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

        compose.onNodeWithText("Playback options").performClick()
        compose.onNodeWithText("Automatic").assertIsOn()
        compose.onNodeWithText("English").assertIsOff()
        compose.onNodeWithText("Deutsch").assertIsOff().performClick()
        compose.runOnIdle {
            assertEquals(listOf(1), player.trackSelectionParameters.overrides.getValue(player.audio).trackIndices)
            assertEquals(player.subtitleOverride, player.trackSelectionParameters.overrides[player.text])
            assertEquals(AudioSelection.Track(player.audio.getFormat(1).toAudioTrackIdentity()), selections.single())
        }
        compose.onNodeWithText("Playback options").performClick()
        compose.onNodeWithText("Deutsch").assertIsOn()
        compose.onNodeWithText("English").assertIsOff()
        compose.onNodeWithText("Automatic").assertIsOff().performClick()
        compose.runOnIdle {
            assertEquals(AudioSelection.Automatic, selections.last())
            assertEquals(mapOf(player.text to player.subtitleOverride), player.trackSelectionParameters.overrides)
        }
    }

    @Test
    fun unavailableCommandsDisableSpeedAndAudioChoices() {
        val player = OptionsPlayer(canChangeOptions = false)
        compose.setContent {
            PutioTheme { MobilePlaybackOptions(player, {}, {}, {}, {}) }
        }

        compose.onNodeWithText("Playback options").performClick()
        listOf("0.75×", "1×", "1.25×", "1.5×", "2×", "Automatic", "English", "Deutsch").forEach { label ->
            compose.onNodeWithText(label).assertIsNotEnabled()
        }
    }

    @Test
    fun singleAudioTrackOffersSpeedWithoutAnAudioChooser() {
        val player = OptionsPlayer(singleAudioTrack = true)
        compose.setContent {
            PutioTheme { MobilePlaybackOptions(player, {}, {}, {}, {}) }
        }

        compose.onNodeWithText("Playback options").performClick()
        compose.onNodeWithText("1.5×").assertIsEnabled()
        compose.onNodeWithText("Automatic").assertDoesNotExist()
        compose.onNodeWithText("English").assertDoesNotExist()
        compose.onNodeWithText("Deutsch").assertDoesNotExist()
        compose.onNodeWithText("Audio track").assertDoesNotExist()
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
) : SimpleBasePlayer(Looper.getMainLooper()) {
    val audio = if (singleAudioTrack) {
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
