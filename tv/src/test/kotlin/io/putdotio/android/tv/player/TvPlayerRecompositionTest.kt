package io.putdotio.android.tv.player

import android.content.ContextWrapper
import android.content.res.Resources
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.playback.SUBTITLE_CUES_TAG
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.tv.setTvContent
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalTestApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvPlayerRecompositionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun seekBarUsesDrawBehindAndProgressStaysOutsideControlsRoot() {
        val player = FakePlayer().apply {
            setMediaItem(MediaItem.fromUri(SOURCE_URL))
            prepare()
        }
        val tables = Collections.newSetFromMap(WeakHashMap<CompositionData, Boolean>())
        val rootRecompositions = AtomicInteger(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionTables provides tables) {
                PutioTvTheme {
                    @Suppress("UnnecessaryComposedModifier")
                    val rootTracker = Modifier.composed {
                        rootRecompositions.incrementAndGet()
                        Modifier
                    }
                    TvPlayerControls(
                        player = player,
                        title = "Sintel.mp4",
                        paused = false,
                        seeking = false,
                        startPositionMillis = 0L,
                        listingDurationMillis = 840_000L,
                        chips = listOf(TvPlayerControl.Subtitles),
                        chipLabels = TvPlayerChipLabels("Audio", "Subtitles", "1x", ""),
                        focused = TvPlayerControl.SeekBar,
                        modifier = rootTracker,
                    )
                }
            }
        }
        compose.settle()
        val baselineRoot = rootRecompositions.get()

        compose.runOnIdle { player.advanceTo(10_000L) }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()

        compose.runOnIdle { player.advanceTo(20_000L) }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()

        compose.onNodeWithText("0:20").assertIsDisplayed()
        compose.onNodeWithTag(TV_PLAYER_SEEK_BAR_TAG).assert(progressMatches(20_000f / 840_000f))
        assertEquals("500 ms ticks must not recompose TvPlayerControls root", baselineRoot, rootRecompositions.get())
        assertTrue("TvPlayerControls must not create BoxWithConstraints subcompositions", tables.isEmpty())
    }

    @Test
    fun seekBarRendersFillAndKnobInLtrAndRtlIndependentlyOfFocus() {
        var fraction by mutableFloatStateOf(0f)
        var focused by mutableStateOf(false)
        var layoutDirection by mutableStateOf(LayoutDirection.Ltr)
        compose.setTvContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(32.dp).testTag("seek-host"),
                    contentAlignment = Alignment.Center,
                ) {
                    TvSeekBar(fraction = fraction, description = "Seek", scrubbing = false, focused = focused)
                }
            }
        }
        compose.onNodeWithTag(TV_PLAYER_SEEK_BAR_TAG).assert(progressMatches(0f))

        val (knobPastFill, aboveTrack, ringOffset) = with(compose.density) {
            Triple(4.dp.roundToPx(), 5.dp.roundToPx(), 10.dp.roundToPx())
        }
        val yellow = PutioDesignTokens.yellowSolid
        fraction = 0.25f
        compose.waitForIdle()
        compose.onNodeWithTag(TV_PLAYER_SEEK_BAR_TAG).assert(progressMatches(0.25f))
        val ltr = compose.onNodeWithTag("seek-host").captureToImage().toPixelMap()
        val midY = ltr.height / 2
        assertEquals(yellow, ltr[ltr.width / 8, midY])
        assertNotEquals(yellow, ltr[ltr.width * 3 / 4, midY])
        assertEquals(yellow, ltr[ltr.width / 4 + knobPastFill, midY])
        assertEquals(yellow, ltr[ltr.width / 4, midY - aboveTrack])
        assertNotEquals(yellow, ltr[ltr.width / 2, midY - aboveTrack])
        assertNotEquals(yellow, ltr[ltr.width * 3 / 4, midY - aboveTrack])

        layoutDirection = LayoutDirection.Rtl
        compose.waitForIdle()
        val rtl = compose.onNodeWithTag("seek-host").captureToImage().toPixelMap()
        assertEquals(yellow, rtl[rtl.width * 7 / 8, midY])
        assertNotEquals(yellow, rtl[rtl.width / 4, midY])
        assertEquals(yellow, rtl[rtl.width * 3 / 4 - knobPastFill, midY])
        assertEquals(yellow, rtl[rtl.width * 3 / 4, midY - aboveTrack])
        assertNotEquals(yellow, rtl[rtl.width / 4, midY - aboveTrack])

        layoutDirection = LayoutDirection.Ltr
        focused = true
        compose.waitForIdle()
        val focusedMap = compose.onNodeWithTag("seek-host").captureToImage().toPixelMap()
        assertNotEquals(ltr[ltr.width / 4, midY - ringOffset], focusedMap[ltr.width / 4, midY - ringOffset])
        assertNotEquals(ltr[ltr.width / 4, midY + ringOffset], focusedMap[ltr.width / 4, midY + ringOffset])
        assertNotEquals(yellow, focusedMap[ltr.width / 4, midY - ringOffset])
        assertNotEquals(yellow, focusedMap[ltr.width / 4, midY + ringOffset])
        assertEquals(ltr[ltr.width * 3 / 4, midY - ringOffset], focusedMap[ltr.width * 3 / 4, midY - ringOffset])

        fraction = 1f
        focused = false
        compose.waitForIdle()
        compose.onNodeWithTag(TV_PLAYER_SEEK_BAR_TAG).assert(progressMatches(1f))
    }

    @Test
    fun subtitleCueEmissionsAndDelayedTicksDoNotRecomposeTvReadyPlayer() {
        val player = TrackPlayer(audio = null, text = subtitleTrackGroup())
        val chipLabelLookups = AtomicInteger(0)
        mountSubtitlePlayer(player, chipLabelLookups)
        if (compose.onAllNodesWithTag(TV_PLAYER_CONTROLS_TAG).fetchSemanticsNodes().isNotEmpty()) compose.back()
        compose.onAllNodesWithTag(TV_PLAYER_CONTROLS_TAG).assertCountEquals(0)

        val afterTracksBaseline = chipLabelLookups.get()
        compose.onAllNodesWithTag(SUBTITLE_CUES_TAG).assertCountEquals(0)
        compose.runOnIdle { player.emitCue("HHHH") }
        compose.settle()
        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertIsDisplayed()
        val initialCue = compose.onNodeWithTag(SUBTITLE_CUES_TAG).captureToImage()

        compose.runOnIdle { player.emitCue("Second cue") }
        compose.settle()
        compose.runOnIdle { player.emitCue("Third cue") }
        compose.settle()
        val thirdCue = compose.onNodeWithTag(SUBTITLE_CUES_TAG).captureToImage()
        assertEquals(afterTracksBaseline, chipLabelLookups.get())

        openSubtitlesAndAddDelay()
        compose.runOnIdle {
            player.pause()
            player.advanceTo(1_000L)
            player.emitCue("")
        }
        compose.settle()
        if (compose.onAllNodesWithTag(TV_PLAYER_CONTROLS_TAG).fetchSemanticsNodes().isNotEmpty()) compose.back()
        compose.onAllNodesWithTag(TV_PLAYER_CONTROLS_TAG).assertCountEquals(0)
        compose.runOnIdle { player.advanceTo(1_200L) }
        compose.mainClock.advanceTimeBy(150L)
        compose.settle()
        val cleanBeforeDelayedCue = compose.onNodeWithTag(SUBTITLE_CUES_TAG).captureToImage()
        assertTrue("Cleared cue must differ from HHHH", pixelsDiffer(initialCue, cleanBeforeDelayedCue))
        assertTrue("Cleared cue must differ from Third cue", pixelsDiffer(thirdCue, cleanBeforeDelayedCue))

        val afterDelayBaseline = chipLabelLookups.get()
        compose.runOnIdle { player.emitCue("HHHH") }
        compose.settle()
        val premature = compose.onNodeWithTag(SUBTITLE_CUES_TAG).captureToImage()
        assertTrue("Delayed cue rendered prematurely", !pixelsDiffer(cleanBeforeDelayedCue, premature))
        assertEquals(afterDelayBaseline, chipLabelLookups.get())

        compose.runOnIdle { player.advanceTo(1_350L) }
        compose.mainClock.advanceTimeBy(150L)
        compose.settle()
        compose.onNodeWithTag(SUBTITLE_CUES_TAG).assertIsDisplayed()
        val delayedImage = compose.onNodeWithTag(SUBTITLE_CUES_TAG).captureToImage()
        assertTrue("Delayed cue did not appear", pixelsDiffer(cleanBeforeDelayedCue, delayedImage))
        assertTrue("Delayed cue must match initial HHHH rendering", !pixelsDiffer(initialCue, delayedImage))
        assertEquals(afterDelayBaseline, chipLabelLookups.get())
    }

    private fun mountSubtitlePlayer(player: TrackPlayer, chipLabelLookups: AtomicInteger) {
        val countingResources = countingResourcesForChipLabels(chipLabelLookups)
        val wrappedContext = object : ContextWrapper(compose.activity) {
            override fun getResources(): Resources = countingResources
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalResources provides countingResources,
                LocalContext provides wrappedContext,
            ) {
                PutioTvTheme {
                    TvPlayerScreen(
                        state = readyState(),
                        onBack = {},
                        onRetry = {},
                        onPlayerFailure = { _, _ -> },
                        playerFactory = { _, _ -> player },
                        subtitleStartupPolicy = SubtitleStartupPolicy(
                            showSubtitles = true,
                            autoSelectSubtitles = true,
                        ),
                    )
                }
            }
        }
        compose.settle()
        compose.runOnIdle { player.play() }
        compose.settle()
    }

    private fun pixelsDiffer(
        first: androidx.compose.ui.graphics.ImageBitmap,
        second: androidx.compose.ui.graphics.ImageBitmap,
    ): Boolean {
        val a = first.toPixelMap()
        val b = second.toPixelMap()
        return (0 until b.height).any { y -> (0 until b.width).any { x -> b[x, y] != a[x, y] } }
    }

    private fun openSubtitlesAndAddDelay() {
        if (compose.onAllNodesWithTag(TV_PLAYER_CONTROLS_TAG).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionDown) }
            compose.settle()
        }
        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.settle()
        repeat(3) {
            val selected = compose.onAllNodes(hasContentDescription("Subtitles:", substring = true) and isSelected())
                .fetchSemanticsNodes()
            if (selected.isEmpty()) {
                compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionRight) }
                compose.settle()
            }
        }
        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.settle()
        compose.onNode(hasText("Size and timing") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.settle()
        compose.onNode(hasText("+0.1") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.settle()
        backInDialog()
        backInDialog()
    }

    private fun backInDialog() {
        compose.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            val down = AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_BACK)
            dialog.dispatchKeyEvent(down)
            dialog.dispatchKeyEvent(AndroidKeyEvent.changeAction(down, AndroidKeyEvent.ACTION_UP))
        }
        compose.settle()
    }

    @Suppress("DEPRECATION")
    private fun countingResourcesForChipLabels(counter: AtomicInteger): Resources {
        val base = compose.activity.resources
        return object : Resources(base.assets, base.displayMetrics, base.configuration) {
            override fun getString(id: Int): String {
                if (id == R.string.tv_player_audio || id == R.string.tv_player_subtitles_off) {
                    counter.incrementAndGet()
                }
                return super.getString(id)
            }

            override fun getString(id: Int, vararg formatArgs: Any?): String {
                if (id == R.string.tv_player_speed_value) counter.incrementAndGet()
                return super.getString(id, *formatArgs)
            }
        }
    }

    private fun subtitleTrackGroup() = TrackGroup(
        "text-one",
        Format.Builder()
            .setId("t-en")
            .setLanguage("en")
            .setLabel("English")
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .build(),
    )

    private fun progressMatches(expected: Float) =
        SemanticsMatcher("seek fraction $expected") {
            val info = it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
            info != null && abs(info.current - expected) < 0.001f
        }
}
