package io.putdotio.android.tv.player

import androidx.activity.ComponentActivity
import io.putdotio.android.tv.setTvContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.playback.PLAYBACK_REPORTING_LEASE_KEY
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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
    fun aSavedPositionWaitingForItsAnswerShowsTheFilenameAndNoPrompt() {
        compose.setTvContent {
            TvPlayerScreen(
                state = readyState().copyForTest(
                    content = PlaybackContent.AwaitingResume(source(startFromSeconds = SAVED_SECONDS.toDouble())),
                ),
                onBack = {},
                onRetry = {},
                onPlayerFailure = { _, _ -> },
                playerFactory = { _, _ -> error("No player before the position is answered") },
            )
        }
        compose.onNodeWithText("Sintel.mp4").assertIsDisplayed()
        compose.onNodeWithText(RESUME_TITLE).assertDoesNotExist()
        compose.onNodeWithText(RESUME_LABEL).assertDoesNotExist()
    }

    @Test
    fun leavingPlaybackWritesTheExitPositionUnderTheItemsLease() {
        val player = FakePlayer()
        val writes = mutableListOf<Pair<Long, Double>>()
        val reporting = reporting(writes)
        var showing by mutableStateOf(true)
        compose.setTvContent {
            if (showing) {
                TvPlayerScreen(
                    state = readyState(useStartFrom = true),
                    onBack = { showing = false },
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> player },
                    reporter = reporting,
                )
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
            val (fileId, seconds) = writes.single()
            assertEquals(9L, fileId)
            assertTrue("Exit position $seconds s", seconds in 61.0..62.0)
        }
        reporting.close()
    }

    @Test
    fun backgroundAudioKeepsWritingItsPositionEveryTickWhileTheAppIsStopped() {
        val player = FakePlayer()
        val writes = mutableListOf<Pair<Long, Double>>()
        val reporting = reporting(writes)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        compose.setTvContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TvPlayerScreen(
                    state = readyState(name = "song.mp3", useStartFrom = true, mediaType = PlaybackMediaType.AUDIO),
                    onBack = {},
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> player },
                    reporter = reporting,
                    onBackground = {},
                )
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { player.advanceTo(31_000L) }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))

        compose.runOnIdle {
            assertTrue(player.playWhenReady)
            assertEquals(listOf(9L to 31.0), writes)
        }
        reporting.close()
    }

    @Test
    fun aSourceResolvedWithResumeOffWritesNothing() {
        val player = FakePlayer()
        val writes = mutableListOf<Pair<Long, Double>>()
        val reporting = reporting(writes)
        var showing by mutableStateOf(true)
        compose.setTvContent {
            if (showing) {
                TvPlayerScreen(
                    state = readyState(useStartFrom = false),
                    onBack = { showing = false },
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = { _, _ -> player },
                    reporter = reporting,
                )
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
}
