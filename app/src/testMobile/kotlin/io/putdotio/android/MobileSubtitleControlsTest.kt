package io.putdotio.android

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Tracks
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.design.PutioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun ImageBitmap.hasVisiblePixel(): Boolean {
    val pixels = toPixelMap()
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (pixels[x, y].alpha > 0f) return true
        }
    }
    return false
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileSubtitleControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun labeledCaptionEntryExplainsEmptyTracksAndRetainsOffPreference() {
        val player = RecordingPlayer()
        var selection: SubtitleSelection? = null
        compose.setContent {
            PutioTheme {
                MobileSubtitleControls(
                    player,
                    TrackSelectionParameters.Builder().build(),
                    { selection = it },
                    {}, {}, {},
                    showLabel = true,
                )
            }
        }

        compose.onNodeWithText("Captions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Captions").performClick()
        compose.onNodeWithText("No captions available").assertIsDisplayed()
        compose.onNodeWithText("Automatic").assertIsDisplayed()
        compose.onNodeWithText("Off").performClick()
        compose.runOnIdle {
            assertEquals(SubtitleSelection.Off, selection)
            assertTrue(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
        }
    }

    @Test
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun labeledCaptionEntryUsesDiscoveredSupportedTracks() {
        val player = CaptionControlPlayer()
        var selection: SubtitleSelection? = null
        compose.setContent {
            PutioTheme {
                MobileSubtitleControls(
                    player,
                    TrackSelectionParameters.Builder().build(),
                    { selection = it },
                    {}, {}, {},
                    showLabel = true,
                )
            }
        }
        compose.onNodeWithText("Captions").performClick()
        compose.onNodeWithText("No captions available").assertIsDisplayed()
        val group = TrackGroup(
            Format.Builder().setId("en").setLabel("English").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            Format.Builder().setId("de").setLabel("Deutsch").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
        )
        compose.runOnIdle {
            player.discover(
                Tracks(
                    listOf(
                        Tracks.Group(
                            group,
                            false,
                            intArrayOf(C.FORMAT_HANDLED, C.FORMAT_UNSUPPORTED_SUBTYPE),
                            booleanArrayOf(false, false),
                        ),
                    ),
                ),
            )
        }

        compose.onNodeWithText("No captions available").assertDoesNotExist()
        compose.onNodeWithText("Deutsch").assertDoesNotExist()
        compose.onNodeWithText("English").performClick()
        compose.runOnIdle {
            assertEquals(SubtitleSelection.Track(group.getFormat(0).toSubtitleTrackIdentity()), selection)
            assertEquals(listOf(0), player.trackSelectionParameters.overrides.getValue(group).trackIndices)
        }
    }

    @Test
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun subtitleSheetDisablesThenRestoresAutomaticDefaults() {
        val player = RecordingPlayer()
        val defaults = TrackSelectionParameters.Builder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        )
            .setPreferredTextLanguage("en").setPreferredAudioLanguage("de").build()
        val selections = mutableListOf<SubtitleSelection>()
        val visibility = mutableListOf<Boolean>()
        compose.runOnUiThread {
            player.trackSelectionParameters = defaults.withSubtitleSelection(SubtitleSelection.Automatic, emptyList())
        }
        compose.setContent {
            PutioTheme {
                MobileSubtitleControls(
                    player = player,
                    defaultTrackSelection = defaults,
                    onSubtitleSelectionChanged = selections::add,
                    onMenuVisibilityChanged = visibility::add,
                    onKeyboardNavigation = {},
                    onPointerNavigation = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Choose subtitles").performClick()
        compose.onNodeWithText("Automatic").assertIsSelected()
        compose.onNodeWithText("Off").performClick()
        compose.runOnIdle {
            assertTrue(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
            assertEquals(listOf(SubtitleSelection.Off), selections)
        }
        compose.onNodeWithContentDescription("Choose subtitles")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Subtitles off"))
            .performClick()
        compose.onNodeWithText("Off").assertIsSelected()
        compose.onNodeWithText("Automatic").performClick()
        compose.runOnIdle {
            assertTrue(C.TRACK_TYPE_TEXT !in player.trackSelectionParameters.disabledTrackTypes)
            assertEquals(listOf("en"), player.trackSelectionParameters.preferredTextLanguages)
            assertEquals(listOf("de"), player.trackSelectionParameters.preferredAudioLanguages)
            assertEquals(listOf(SubtitleSelection.Off, SubtitleSelection.Automatic), selections)
            assertEquals(listOf(true, false, true, false), visibility)
        }
    }

    @Test
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun forcedOnlyStartupDoesNotClaimAutomaticSelection() {
        val player = RecordingPlayer()
        val defaults = TrackSelectionParameters.Builder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        ).build()
        compose.runOnUiThread {
            player.trackSelectionParameters = restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                systemCaptionsEnabled = false,
            )
        }
        compose.setContent {
            PutioTheme {
                MobileSubtitleControls(player, defaults, {}, {}, {}, {})
            }
        }
        compose.onNodeWithContentDescription("Choose subtitles")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Subtitles off"))
            .performClick()
        compose.onNodeWithText("Off").assertIsNotSelected()
        compose.onNodeWithText("Automatic").assertIsNotSelected().performClick()
        compose.runOnIdle { assertTrue(player.trackSelectionParameters.selectTextByDefault) }
        compose.onNodeWithContentDescription("Choose subtitles").performClick()
        compose.onNodeWithText("Automatic").assertIsSelected()
    }

    @Test
    fun selectedSubtitleCueIsRendered() {
        val cue = Cue.Builder().setText("A rendered subtitle").build()
        compose.setContent {
            PutioTheme {
                MobileSubtitleCueOverlay(
                    cues = listOf(cue),
                    videoAspectRatio = 16f / 9f,
                    modifier = Modifier.requiredSize(320.dp, 180.dp),
                )
            }
        }

        compose.onNodeWithTag(MOBILE_SUBTITLE_CUES_TAG)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(320.dp)
            .assertHeightIsEqualTo(180.dp)
        assertTrue(compose.onNodeWithTag(MOBILE_SUBTITLE_CUES_TAG).captureToImage().hasVisiblePixel())
    }

    @Test
    fun subtitleCuesWaitForVideoDimensionsAfterPlayerReplacement() {
        var aspectRatio by mutableStateOf<Float?>(null)
        val cues = listOf(Cue.Builder().setText("A retained subtitle").build())
        compose.setContent {
            PutioTheme {
                MobileSubtitleCueOverlay(
                    cues = cues,
                    videoAspectRatio = aspectRatio,
                    modifier = Modifier.requiredSize(320.dp, 640.dp),
                )
            }
        }

        compose.onNodeWithTag(MOBILE_SUBTITLE_CUES_TAG).assertDoesNotExist()
        compose.runOnIdle { aspectRatio = 16f / 9f }
        compose.onNodeWithTag(MOBILE_SUBTITLE_CUES_TAG)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(320.dp)
            .assertHeightIsEqualTo(180.dp)
        assertTrue(compose.onNodeWithTag(MOBILE_SUBTITLE_CUES_TAG).captureToImage().hasVisiblePixel())
        compose.runOnIdle { aspectRatio = null }
        compose.onNodeWithTag(MOBILE_SUBTITLE_CUES_TAG).assertDoesNotExist()
    }

    @Test
    fun bitmapSubtitleCueIsRendered() {
        val cue =
            Cue.Builder()
                .setBitmap(
                    Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.RED)
                    },
                )
                .setPosition(0.25f)
                .setLine(0.75f, Cue.LINE_TYPE_FRACTION)
                .setSize(0.5f)
                .setBitmapHeight(0.1f)
                .build()
        compose.setContent {
            PutioTheme {
                MobileSubtitleCueOverlay(
                    cues = listOf(cue),
                    videoAspectRatio = 16f / 9f,
                    modifier = Modifier.requiredSize(320.dp, 180.dp),
                )
            }
        }

        compose.onNodeWithTag(MOBILE_SUBTITLE_CUES_TAG)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(320.dp)
            .assertHeightIsEqualTo(180.dp)
        assertTrue(compose.onNodeWithTag(MOBILE_SUBTITLE_CUES_TAG).captureToImage().hasVisiblePixel())
    }

    @Test
    fun subtitleCueLayerDoesNotBlockUnderlyingPlayerTouches() {
        var taps = 0
        var interactions = 0
        compose.setContent {
            PutioTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .observePlayerControlInteraction(
                            onInteractionChanged = {},
                            onActivity = { interactions += 1 },
                        ),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(0.5f)
                            .testTag("player-touch-target")
                            .pointerInput(Unit) {
                                detectTapGestures { taps += 1 }
                            },
                    )
                    MobileSubtitleCueOverlay(
                        cues = listOf(Cue.Builder().setText("Visible subtitle").build()),
                        videoAspectRatio = 16f / 9f,
                        modifier = Modifier.zIndex(1f),
                    )
                }
            }
        }

        compose.onNodeWithTag("player-touch-target").performTouchInput { click() }
        assertEquals(1, taps)
        assertEquals(1, interactions)
    }

    @Test
    fun selectedSubtitleTrackExposesCheckedState() {
        val group =
            TrackGroup(
                Format.Builder().setId("en").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            )
        compose.setContent {
            PutioTheme {
                MobileSubtitleTrackOption(
                    track =
                        MobileSubtitleTrack(
                            group = group,
                            trackIndex = 0,
                            label = "English",
                            selected = true,
                        ),
                    onClick = {},
                )
            }
        }

        compose.onNodeWithText("English").assertIsSelected()
    }

    @Test
    fun subtitleTrackOptionForwardsPointerAndKeyboardModality() {
        val group =
            TrackGroup(
                Format.Builder().setId("en").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            )
        var pointerEvents = 0
        var keyEvents = 0
        lateinit var inputModeManager: InputModeManager
        compose.setContent {
            inputModeManager = LocalInputModeManager.current
            PutioTheme {
                MobileSubtitleTrackOption(
                    track = MobileSubtitleTrack(group, 0, label = "English", selected = false),
                    onClick = {},
                    modifier =
                        Modifier
                            .observePlayerControlInteraction(
                                onInteractionChanged = { if (it) pointerEvents += 1 },
                                onActivity = {},
                            ).observePlayerControlKeyActivity { keyEvents += 1 },
                )
            }
        }

        compose.onNodeWithText("English").performTouchInput { click() }
        compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithText("English")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }

        assertEquals(1, pointerEvents)
        assertEquals(1, keyEvents)
    }
}

@UnstableApi
private class CaptionControlPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var state = State.Builder()
        .setAvailableCommands(androidx.media3.common.Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(listOf(MediaItemData.Builder("captions").build()))
        .setCurrentMediaItemIndex(0)
        .build()

    override fun getState(): State = state

    fun discover(tracks: Tracks) {
        state = state.buildUpon()
            .setPlaylist(state.playlist.map { it.buildUpon().setTracks(tracks).build() })
            .build()
        invalidateState()
    }

    override fun handleSetTrackSelectionParameters(parameters: TrackSelectionParameters): ListenableFuture<*> {
        state = state.buildUpon().setTrackSelectionParameters(parameters).build()
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}
