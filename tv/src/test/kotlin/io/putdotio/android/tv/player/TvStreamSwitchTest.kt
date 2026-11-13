package io.putdotio.android.tv.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFallbackReason
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.files.PlaybackSourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvStreamSwitchTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val players = mutableListOf<TrackPlayer>()
    private var pinned = true
    private val forgotten = mutableListOf<FilesItemId>()
    private val controller = PlaybackController(
        PlaybackTarget(FilesItemId(9), "Sintel.mkv", PlaybackMediaType.VIDEO),
        object : PlaybackRepository {
            override suspend fun resolve(
                target: PlaybackTarget,
                convertedOnly: Boolean,
            ): PlaybackRepositoryResult<PlaybackResolution> {
                val original = !convertedOnly && (target.method == StreamingMethod.File || !pinned)
                val kind = if (original) PlaybackSourceKind.ORIGINAL else PlaybackSourceKind.HLS
                return PlaybackRepositoryResult.Success(
                    PlaybackResolution.Ready(source().copy(kind = kind), switchedToConverted = !original && pinned),
                )
            }

            override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended

            override fun forgetSwitchedToConverted(target: PlaybackTarget) {
                pinned = false
                forgotten += target.fileId
            }
        },
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    @After
    fun close() = controller.close()

    @Test
    fun aSwitchedVideoGoesBackToItsOriginalAndKeepsSpeedAndSubtitleTimingThroughAFallback() {
        show()
        chip(STREAM).assertIsDisplayed().assert(describedAs("Stream: Converted"))

        openSheet(SPEED)
        choose("2×")
        backInDialog()
        openSheet(SUBTITLES)
        choose("Size and timing")
        repeat(5) { choose("+0.1") }
        backInDialog()
        backInDialog()

        compose.runOnIdle {
            players.last().pause()
            players.last().advanceTo(42_000L)
        }
        openSheet(STREAM)
        compose.onNode(hasText("Converted") and hasClickAction()).assertIsSelected()
        choose("Original")
        compose.runOnIdle {
            assertEquals(PlaybackSourceKind.ORIGINAL, readyKind())
            assertEquals(listOf(FilesItemId(9)), forgotten)
            assertEquals(2, players.size)
            assertEquals(42_000L, controller.state.value.resumePositionMillis)
            assertEquals(42_000L, players.last().currentPosition)
            assertEquals(2f, players.last().playbackParameters.speed)
        }
        assertOffset("+0.5 s")
        chip(STREAM).assert(describedAs("Stream: Original"))

        compose.runOnIdle {
            controller.dispatch(PlaybackEvent.OriginalRejected(PlaybackFallbackReason.VideoUnsupported, 50_000L))
        }
        settle()
        compose.runOnIdle {
            assertEquals(PlaybackSourceKind.HLS, readyKind())
            assertEquals(3, players.size)
            assertEquals(2f, players.last().playbackParameters.speed)
        }
        assertOffset("+0.5 s")
        chip(STREAM).assertDoesNotExist()
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setTvContent {
            TvPlaybackRoute(
                controller = controller,
                onExit = {},
                onSessionRejected = {},
                playerFactory = { _, _ -> TrackPlayer().also(players::add) },
                subtitleStartupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true),
            )
        }
        settle()
    }

    private fun assertOffset(value: String) {
        openSheet(SUBTITLES)
        choose("Size and timing")
        compose.onNodeWithText(value).assertIsDisplayed()
        backInDialog()
        backInDialog()
    }

    private fun openSheet(chip: String) {
        if (CHIPS.none(::chipFocused)) {
            key(Key.DirectionDown)
            key(Key.DirectionDown)
        }
        repeat(CHIPS.size) { if (!chipFocused(chip)) key(Key.DirectionLeft) }
        repeat(CHIPS.size) { if (!chipFocused(chip)) key(Key.DirectionRight) }
        chip(chip).assertIsSelected()
        key(Key.DirectionCenter)
    }

    private fun chipFocused(prefix: String): Boolean =
        compose.onAllNodesWithContentDescription(prefix, substring = true).fetchSemanticsNodes()
            .any { it.config.getOrNull(SemanticsProperties.Selected) == true }

    private fun chip(prefix: String) = compose.onNodeWithContentDescription(prefix, substring = true)

    private fun describedAs(value: String) =
        SemanticsMatcher("described as $value") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.any { description -> description == value } == true
        }

    private fun choose(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun key(key: Key) {
        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(key) }
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

    private fun readyKind(): PlaybackSourceKind? =
        (controller.state.value.content as? PlaybackContent.Ready)?.source?.kind

    private companion object {
        const val SETTLE_MILLIS = 50L
        const val SUBTITLES = "Subtitles:"
        const val SPEED = "Playback speed"
        const val STREAM = "Stream:"
        val CHIPS = listOf("Audio:", SUBTITLES, SPEED, STREAM)
    }
}
