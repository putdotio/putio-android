package io.putdotio.android.tv.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlayerConversionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aConvertingVideoPlaysOnItsOwnOnceTheConversionFinishes() {
        val player = FakePlayer()
        val answers = ArrayDeque(
            listOf(
                PlaybackResolution.Conversion(io.putdotio.sdk.files.PlaybackConversionState.Queued),
                PlaybackResolution.Conversion(io.putdotio.sdk.files.PlaybackConversionState.Converting(60.0)),
                PlaybackResolution.Ready(source()),
            ),
        )
        var resolutions = 0
        val controller = PlaybackController(
            PlaybackTarget(FilesItemId(9), "Sintel.mp4", PlaybackMediaType.VIDEO),
            object : PlaybackRepository {
                override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
                    resolutions += 1
                    return PlaybackRepositoryResult.Success(answers.removeFirst())
                }

                override suspend fun findNextVideo(target: PlaybackTarget) = error("No next video expected")
            },
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackRoute(
                    controller = controller,
                    onExit = {},
                    onSessionRejected = {},
                    playerFactory = { _, _ -> player },
                )
            }
        }
        compose.settle()
        compose.onNodeWithTag(TV_CONVERSION_STATUS_TAG).assertTextEquals("In queue…")

        compose.mainClock.advanceTimeBy(io.putdotio.android.playback.PLAYBACK_CONVERSION_POLL_MILLIS)
        compose.settle()
        compose.onNodeWithTag(TV_CONVERSION_STATUS_TAG).assertTextEquals("60%")
        compose.runOnIdle { assertTrue("Nothing plays yet", player.mediaItems.isEmpty()) }

        compose.mainClock.advanceTimeBy(io.putdotio.android.playback.PLAYBACK_CONVERSION_POLL_MILLIS)
        compose.settle()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        compose.runOnIdle {
            assertEquals(3, resolutions)
            assertTrue(player.prepared)
            assertTrue(player.playWhenReady)
        }
    }

    @Test
    fun aConversionThatReadsTheSameStatusAgainKeepsPolling() {
        val player = FakePlayer()
        val queued = PlaybackResolution.Conversion(io.putdotio.sdk.files.PlaybackConversionState.Queued)
        val answers = ArrayDeque(listOf(queued, queued, queued, PlaybackResolution.Ready(source())))
        var resolutions = 0
        val controller = PlaybackController(
            PlaybackTarget(FilesItemId(9), "Sintel.mp4", PlaybackMediaType.VIDEO),
            object : PlaybackRepository {
                override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
                    resolutions += 1
                    return PlaybackRepositoryResult.Success(answers.removeFirst())
                }

                override suspend fun findNextVideo(target: PlaybackTarget) = error("No next video expected")
            },
            // Each read settles before the next frame, so the screen never sees it in flight.
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackRoute(
                    controller = controller,
                    onExit = {},
                    onSessionRejected = {},
                    playerFactory = { _, _ -> player },
                )
            }
        }
        compose.settle()
        repeat(2) { poll ->
            compose.mainClock.advanceTimeBy(io.putdotio.android.playback.PLAYBACK_CONVERSION_POLL_MILLIS)
            compose.settle()
            compose.onNodeWithTag(TV_CONVERSION_STATUS_TAG).assertTextEquals("In queue…")
            compose.runOnIdle { assertEquals(poll + 2, resolutions) }
        }

        compose.mainClock.advanceTimeBy(io.putdotio.android.playback.PLAYBACK_CONVERSION_POLL_MILLIS)
        compose.settle()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        compose.runOnIdle {
            assertEquals(4, resolutions)
            assertTrue(player.prepared)
        }
    }
}
