package io.putdotio.android

import android.graphics.Bitmap
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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.text.Cue
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    fun subtitleToggleExposesAndUpdatesCheckedState() {
        compose.setContent {
            var enabled by remember { mutableStateOf(true) }
            PutioTheme {
                MobileSubtitleToggle(
                    enabled = enabled,
                    onToggle = { enabled = it },
                )
            }
        }

        compose.onNodeWithText("Subtitles on").assertIsOn().performClick()
        compose.onNodeWithText("Subtitles off").assertIsOff()
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

        compose.onNodeWithText("English").assertIsOn()
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
