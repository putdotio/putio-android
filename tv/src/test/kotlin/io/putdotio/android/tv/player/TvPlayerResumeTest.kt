package io.putdotio.android.tv.player

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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PLAYBACK_REPORTING_LEASE_KEY
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackReducer
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackRequestId
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import kotlin.math.abs
import android.view.KeyEvent as AndroidKeyEvent
import io.putdotio.android.playback.copyForTest

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlayerResumeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aSavedPositionWithoutADurationContinuesWithoutAsking() {
        var resumes = 0
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlayerScreen(
                    state = readyState().copyForTest(
                        content = PlaybackContent.AwaitingResume(source(startFromSeconds = 30.0)),
                    ),
                    onBack = {},
                    onRetry = {},
                    onResume = { resumes += 1 },
                    onRestart = { error("Nothing was offered") },
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> error("No player before the choice") },
                )
            }
        }
        compose.runOnIdle { assertEquals(1, resumes) }
        compose.onNodeWithText(CONTINUE_LABEL).assertDoesNotExist()
    }

    @Test
    fun aDurationReadAtResolutionOffersTheChoice() {
        val pending = PlaybackReducer.reduce(
            PlaybackReducer.start(readyState().target).state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(1L),
                PlaybackResolution.Ready(
                    source(startFromSeconds = SAVED_SECONDS.toDouble()),
                    useStartFrom = true,
                    durationSeconds = DURATION_SECONDS.toDouble(),
                ),
            ),
        ).state
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlayerScreen(
                    state = pending,
                    onBack = {},
                    onRetry = {},
                    onResume = { error("The choice is offered") },
                    onRestart = { error("The choice is offered") },
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> error("No player before the choice") },
                )
            }
        }
        compose.onNodeWithText(CONTINUE_LABEL).assertIsDisplayed()
        assertResumeProgress(SAVED_SECONDS / DURATION_SECONDS)
    }

    @Test
    fun theResumeDialogPrefersContinueAndItsBarPreviewsTheFocusedChoice() {
        val player = FakePlayer()
        showResumeRoute(player)
        compose.onNodeWithText("Sintel.mp4").assertIsDisplayed()
        compose.onNodeWithText(CONTINUE_LABEL).assertIsFocused()
        assertResumeProgress(SAVED_SECONDS / DURATION_SECONDS)
        compose.runOnIdle { assertTrue("No player before the choice", player.mediaItems.isEmpty()) }

        compose.onNodeWithText(CONTINUE_LABEL).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText(RESTART_LABEL).assertIsFocused()
        assertResumeProgress(0f)
        compose.onNodeWithText(RESTART_LABEL).performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText(CONTINUE_LABEL).assertIsFocused()
        assertResumeProgress(SAVED_SECONDS / DURATION_SECONDS)

        compose.onNodeWithText(CONTINUE_LABEL).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText(CONTINUE_LABEL).assertDoesNotExist()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        compose.runOnIdle {
            assertEquals(SAVED_SECONDS.toLong() * 1_000L, player.startPositionMillis)
            assertTrue(player.playWhenReady)
        }
    }

    @Test
    fun startFromTheBeginningPlaysFromZero() {
        val player = FakePlayer()
        showResumeRoute(player)
        compose.onNodeWithText(CONTINUE_LABEL).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText(RESTART_LABEL).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText(RESTART_LABEL).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0L, player.startPositionMillis)
            assertTrue(player.playWhenReady)
        }
    }

    @Test
    fun backFromTheResumeDialogLeavesPlaybackWithoutAPlayer() {
        val player = FakePlayer()
        var exits = 0
        showResumeRoute(player, onExit = { exits += 1 })
        compose.onNodeWithText(CONTINUE_LABEL).assertIsFocused()

        // Back reaches the dialog's own window, as the remote's does.
        compose.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            val down = AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_BACK)
            dialog.dispatchKeyEvent(down)
            dialog.dispatchKeyEvent(AndroidKeyEvent.changeAction(down, AndroidKeyEvent.ACTION_UP))
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(1, exits)
            assertTrue("No player after leaving", player.mediaItems.isEmpty())
        }
    }

    @Test
    fun leavingPlaybackWritesTheExitPositionUnderTheItemsLease() {
        val player = FakePlayer()
        val writes = mutableListOf<Pair<Long, Double>>()
        val reporting = reporting(writes)
        var showing by mutableStateOf(true)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                if (showing) {
                    TvPlayerScreen(
                        state = readyState(useStartFrom = true),
                        onBack = { showing = false },
                        onRetry = {},
                        onResume = {},
                        onRestart = {},
                        onPlayerFailure = { _, _ -> },
                        playerFactory = { _, _ -> player },
                        reporter = reporting,
                    )
                }
            }
        }
        compose.runOnIdle {
            val lease = player.mediaItems.single().mediaMetadata.extras?.getString(PLAYBACK_REPORTING_LEASE_KEY)
            assertEquals(reporting.lease(9L), lease)
            player.advanceTo(61_000L)
        }
        compose.back()
        compose.back()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertDoesNotExist()
        compose.runOnIdle {
            // The playing fake's clock moves on a few frames past the jump.
            val (fileId, seconds) = writes.single()
            assertEquals(9L, fileId)
            assertTrue("Exit position $seconds s", seconds in 61.0..62.0)
        }
        reporting.close()
    }

    @Test
    fun aSourceResolvedWithResumeOffWritesNothing() {
        val player = FakePlayer()
        val writes = mutableListOf<Pair<Long, Double>>()
        val reporting = reporting(writes)
        var showing by mutableStateOf(true)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                if (showing) {
                    TvPlayerScreen(
                        state = readyState(useStartFrom = false),
                        onBack = { showing = false },
                        onRetry = {},
                        onResume = {},
                        onRestart = {},
                        onPlayerFailure = { _, _ -> },
                        playerFactory = { _, _ -> player },
                        reporter = reporting,
                    )
                }
            }
        }
        compose.runOnIdle {
            assertNull(player.mediaItems.single().mediaMetadata.extras?.getString(PLAYBACK_REPORTING_LEASE_KEY))
            player.advanceTo(61_000L)
        }
        compose.back()
        compose.back()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertDoesNotExist()
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        reporting.close()
    }

    /** The session route on a real controller whose resolution carries a saved position. */
    private fun showResumeRoute(player: FakePlayer, onExit: () -> Unit = {}) {
        val controller = PlaybackController(
            PlaybackTarget(FilesItemId(9), "Sintel.mp4", PlaybackMediaType.VIDEO, DURATION_SECONDS.toDouble()),
            object : PlaybackRepository {
                override suspend fun resolve(target: PlaybackTarget) = PlaybackRepositoryResult.Success(
                    PlaybackResolution.Ready(source(startFromSeconds = SAVED_SECONDS.toDouble()), useStartFrom = true),
                )

                override suspend fun findNextVideo(target: PlaybackTarget) = error("No next video expected")
            },
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackRoute(
                    controller = controller,
                    onExit = onExit,
                    onSessionRejected = {},
                    playerFactory = { _, _ -> player },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun assertResumeProgress(fraction: Float) {
        compose.onNodeWithTag(TV_PLAYER_RESUME_PROGRESS_TAG, useUnmergedTree = true).assert(
            SemanticsMatcher("progress $fraction") {
                val info = it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
                info != null && abs(info.current - fraction) < 0.001f
            },
        )
    }

    private companion object {
        const val RESTART_LABEL = "Start from the beginning"
    }
}
