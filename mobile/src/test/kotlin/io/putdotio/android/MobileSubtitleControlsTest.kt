package io.putdotio.android

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import io.putdotio.android.playback.SubtitleSelection
import io.putdotio.android.playback.MobileSubtitleAppearanceContent
import io.putdotio.android.playback.MobileSubtitlesChip
import io.putdotio.android.playback.MobileSubtitlesSheetContent
import io.putdotio.android.playback.SubtitleTextSize
import io.putdotio.android.playback.formatSubtitleOffset
import io.putdotio.android.playback.rememberPlayerOptionsSnapshot
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PutioCredentialUrl
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import io.putdotio.android.playback.PlaybackSubtitleTrack
import io.putdotio.android.playback.withSubtitleSelection
import io.putdotio.android.playback.restoreSubtitleSelection
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.toSubtitleTrackIdentity
import io.putdotio.android.playback.SubtitleCueOverlay
import io.putdotio.android.playback.SUBTITLE_CUES_TAG
import io.putdotio.android.playback.MobileSubtitleTrackOption
import io.putdotio.android.playback.observePlayerControlInteraction
import io.putdotio.android.playback.observePlayerControlKeyActivity

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
    fun emptySubtitleSheetExplainsItselfAndRetainsOff() {
        val player = RecordingPlayer()
        var selection: SubtitleSelection? = null
        compose.setContent {
            SubtitleSheet(player, TrackSelectionParameters.Builder().build(), emptyList()) { selection = it }
        }

        compose.onNodeWithContentDescription("Subtitles: Off").assertIsDisplayed()
        compose.onNodeWithText("No captions available").assertIsDisplayed()
        compose.onNodeWithText("In this file").assertDoesNotExist()
        compose.onNode(hasText("Off") and isSelectable()).assertIsSelected().performClick()
        compose.runOnIdle {
            assertEquals(SubtitleSelection.Off, selection)
            assertTrue(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
        }
    }

    @Test
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun subtitleSheetGroupsTracksByWhereTheyComeFrom() {
        val player = CaptionControlPlayer()
        val sidecar = listOf(
            subtitle(key = "mkv-en", language = "English", code = "en", source = "mkv", format = "srt"),
            subtitle(key = "folder-de", language = "German", code = "de", source = "folder", format = "srt"),
            subtitle(key = "os-tr", language = "Turkish", code = "tr", source = "opensubtitles", format = "srt"),
        )
        var selection: SubtitleSelection? = null
        val defaults = TrackSelectionParameters.Builder().setPreferredAudioLanguage("de").build()
        compose.runOnIdle { player.trackSelectionParameters = defaults }
        compose.setContent { SubtitleSheet(player, defaults, sidecar) { selection = it } }
        compose.onNodeWithText("No captions available").assertIsDisplayed()
        // Tracks arrive after the sheet is up.
        val group = sourcedSubtitleGroup()
        compose.runOnIdle { player.discover(group) }
        compose.onNodeWithText("No captions available").assertDoesNotExist()

        val rows = listOf(
            "English" to "SRT", "English · SDH" to "VTT", "Deutsch" to "This folder", "Türkçe" to "OpenSubtitles",
        )
        rows.forEach { (name, details) ->
            compose.onNode(hasText(name) and hasText(details) and isSelectable())
                .assertIsDisplayed()
                .assertIsNotSelected()
        }
        val order = listOf("Off", "In this file", "English", "English · SDH", "Found by put.io", "Deutsch", "Türkçe")
        val tops = order.map { text ->
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().maxOf { it.boundsInRoot.top }
        }
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("Français").assertDoesNotExist()

        compose.onNode(hasText("Türkçe") and isSelectable()).performClick()
        compose.runOnIdle {
            assertEquals(SubtitleSelection.Track(group.getFormat(0).toSubtitleTrackIdentity()), selection)
            assertEquals(listOf(0), player.trackSelectionParameters.overrides.getValue(group).trackIndices)
        }
        compose.onNode(hasText("Türkçe") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Subtitles: Türkçe").assertIsDisplayed()
        compose.onNode(hasText("Off") and isSelectable()).assertIsNotSelected().performClick()
        compose.runOnIdle { assertTrue(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) }
        compose.onNode(hasText("English · SDH") and isSelectable()).performClick()
        compose.runOnIdle {
            // Subtitle choices leave the audio preference alone.
            assertEquals(listOf("de"), player.trackSelectionParameters.preferredAudioLanguages)
            assertTrue(C.TRACK_TYPE_TEXT !in player.trackSelectionParameters.disabledTrackTypes)
            assertEquals(listOf(2), player.trackSelectionParameters.overrides.getValue(group).trackIndices)
        }
    }

    @Test
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun forcedOnlyStartupReadsAsOff() {
        val player = RecordingPlayer()
        val defaults = TrackSelectionParameters.Builder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        ).build()
        compose.runOnUiThread {
            player.trackSelectionParameters = restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false),
            )
        }
        compose.setContent { SubtitleSheet(player, defaults, emptyList()) {} }
        compose.onNodeWithContentDescription("Subtitles: Off").assertIsDisplayed()
        compose.onNode(hasText("Off") and isSelectable()).assertIsSelected()
    }

    @Test
    fun sizeAndTimingStepsByATenthAndResets() {
        var size = SubtitleTextSize.Medium
        var offset = 500L
        compose.setContent {
            PutioTheme {
                Column {
                    MobileSubtitleAppearanceContent(size, offset, { size = it }, { offset = it })
                }
            }
        }
        compose.onNodeWithText("+0.5 s").assertIsDisplayed()
        compose.onNodeWithContentDescription("Medium").assertIsSelected()
        compose.onNodeWithContentDescription("Extra large").performClick()
        compose.onNodeWithContentDescription("Earlier by 0.1 seconds").performClick()
        compose.runOnIdle {
            assertEquals(SubtitleTextSize.ExtraLarge, size)
            assertEquals(400L, offset)
        }
        compose.onNodeWithText("Reset").performClick()
        compose.runOnIdle { assertEquals(0L, offset) }
        assertEquals("0.0 s", formatSubtitleOffset(0L))
        assertEquals("\u22121.3 s", formatSubtitleOffset(-1_300L))
        assertEquals("+10.0 s", formatSubtitleOffset(10_000L))
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    @androidx.compose.runtime.Composable
    private fun SubtitleSheet(
        player: androidx.media3.common.Player,
        defaults: TrackSelectionParameters,
        sidecar: List<PlaybackSubtitle>,
        onSelection: (SubtitleSelection) -> Unit,
    ) {
        PutioTheme {
            val options = rememberPlayerOptionsSnapshot(player)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MobileSubtitlesChip(options, {})
                MobileSubtitlesSheetContent(
                    player = player,
                    snapshot = options,
                    defaultTrackSelection = defaults,
                    sidecar = sidecar,
                    textSize = SubtitleTextSize.Medium,
                    offsetMillis = 0L,
                    onSubtitleSelectionChanged = onSelection,
                    onAppearance = {},
                    onDone = {},
                )
            }
        }
    }

    /** Four supported tracks, one of them embedded in the stream, and one the device can't render. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun sourcedSubtitleGroup(): TrackGroup = TrackGroup(
        // Media3 prefixes a merged period's format ids with its index.
        Format.Builder().setId("1:os-tr").setLanguage("tr").setLabel("Turkish")
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build(),
        Format.Builder().setId("2:mkv-en").setLanguage("en").setLabel("English")
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build(),
        Format.Builder().setId("embedded").setLanguage("en").setRoleFlags(C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)
            .setSampleMimeType(MimeTypes.TEXT_VTT).build(),
        Format.Builder().setId("3:folder-de").setLanguage("de").setLabel("German")
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build(),
        Format.Builder().setId("unsupported").setLanguage("fr").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
    )

    private fun CaptionControlPlayer.discover(group: TrackGroup) {
        val supported = IntArray(group.length) { C.FORMAT_HANDLED }
        supported[group.length - 1] = C.FORMAT_UNSUPPORTED_SUBTYPE
        discover(Tracks(listOf(Tracks.Group(group, false, supported, BooleanArray(group.length)))))
    }

    private fun subtitle(key: String, language: String, code: String, source: String, format: String) =
        PlaybackSubtitle(
            format = format,
            key = key,
            language = language,
            languageCode = code,
            name = language,
            source = source,
            url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
                .newInstance("https://example.com/$key.srt"),
        )

    @Test
    fun subtitleCuesWaitForVideoDimensionsAfterPlayerReplacement() {
        var aspectRatio by mutableStateOf<Float?>(null)
        val cues = listOf(Cue.Builder().setText("A retained subtitle").build())
        compose.setContent {
            PutioTheme {
                SubtitleCueOverlay(
                    cues = cues,
                    videoAspectRatio = aspectRatio,
                    modifier = Modifier.requiredSize(320.dp, 640.dp),
                )
            }
        }

        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertDoesNotExist()
        compose.runOnIdle { aspectRatio = 16f / 9f }
        compose.onNodeWithTag(SUBTITLE_CUES_TAG)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(320.dp)
            .assertHeightIsEqualTo(180.dp)
        assertTrue(compose.onNodeWithTag(SUBTITLE_CUES_TAG).captureToImage().hasVisiblePixel())
        compose.runOnIdle { aspectRatio = null }
        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertDoesNotExist()
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
                SubtitleCueOverlay(
                    cues = listOf(cue),
                    videoAspectRatio = 16f / 9f,
                    modifier = Modifier.requiredSize(320.dp, 180.dp),
                )
            }
        }

        compose.onNodeWithTag(SUBTITLE_CUES_TAG)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(320.dp)
            .assertHeightIsEqualTo(180.dp)
        assertTrue(compose.onNodeWithTag(SUBTITLE_CUES_TAG).captureToImage().hasVisiblePixel())
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
                    SubtitleCueOverlay(
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
                    track = PlaybackSubtitleTrack(group, 0, label = "English", selected = false),
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
