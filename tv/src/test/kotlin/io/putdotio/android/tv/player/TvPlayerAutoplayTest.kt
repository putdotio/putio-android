package io.putdotio.android.tv.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PLAYBACK_REPORTING_LEASE_KEY
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNextResult
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
class TvPlayerAutoplayTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun withAutoplayAFinishedVideoWritesItsEndAndTheNextInTheFolderPlays() {
        val players = mutableListOf<FakePlayer>()
        val writes = mutableListOf<Pair<Long, Double>>()
        val reporting = reporting(writes)
        var exits = 0
        val lookups = showAutoplayRoute(
            autoplay = true,
            players = players,
            reporting = reporting,
            folder = AutoplayFolder(next = mapOf(9L to PlaybackTarget(FilesItemId(10), "Harbor film 2.mp4"))),
            onExit = { exits += 1 },
        )

        compose.runOnIdle { players.single().end() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(9L), lookups)
            assertEquals(0, exits)
            assertTrue(players.first().released)
            val (fileId, seconds) = writes.single()
            assertEquals("The finished video's end is written under its own lease", 9L, fileId)
            assertEquals(DURATION_SECONDS.toDouble(), seconds, 0.001)
            val next = players.last()
            assertEquals(2, players.size)
            assertEquals("10", next.mediaItems.single().mediaId)
            assertTrue(next.playWhenReady)
            assertEquals(
                reporting.lease(10L),
                next.mediaItems.single().mediaMetadata.extras?.getString(PLAYBACK_REPORTING_LEASE_KEY),
            )
        }
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()

        // The folder's last video leaves playback, once.
        compose.runOnIdle { players.last().end() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(9L, 10L), lookups)
            assertEquals(1, exits)
        }
        reporting.close()
    }

    @Test
    fun withoutAutoplayAFinishedVideoLeavesAsBefore() {
        val players = mutableListOf<FakePlayer>()
        var exits = 0
        val lookups = showAutoplayRoute(
            autoplay = false,
            players = players,
            folder = AutoplayFolder(next = mapOf(9L to PlaybackTarget(FilesItemId(10), "Harbor film 2.mp4"))),
            onExit = { exits += 1 },
        )

        compose.runOnIdle { players.single().end() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, exits)
            assertTrue(lookups.isEmpty())
            assertEquals(1, players.size)
        }
    }

    @Test
    fun anAutoplayedVideoWithASavedPositionAsksWhereToStart() {
        val players = mutableListOf<FakePlayer>()
        showAutoplayRoute(
            autoplay = true,
            players = players,
            folder = AutoplayFolder(
                next = mapOf(
                    9L to PlaybackTarget(
                        FilesItemId(10),
                        "Harbor film 2.mp4",
                        durationSeconds = DURATION_SECONDS.toDouble(),
                    ),
                ),
                savedSeconds = mapOf(10L to SAVED_SECONDS.toDouble()),
            ),
        )

        compose.runOnIdle { players.single().end() }
        compose.waitForIdle()

        compose.onNodeWithText("Harbor film 2.mp4").assertIsDisplayed()
        compose.onNodeWithText(CONTINUE_LABEL).assertIsFocused()
        compose.runOnIdle { assertEquals("No player before the choice", 1, players.size) }
    }

    /** The session route on a real controller over [folder]. Returns the videos next was asked for. */
    private fun showAutoplayRoute(
        autoplay: Boolean,
        players: MutableList<FakePlayer>,
        folder: AutoplayFolder,
        reporting: TvPlaybackReporter = TvPlaybackReporter.None,
        onExit: () -> Unit = {},
    ): List<Long> {
        val lookups = mutableListOf<Long>()
        val controller = PlaybackController(
            PlaybackTarget(FilesItemId(9), "Harbor film.mp4", PlaybackMediaType.VIDEO),
            object : PlaybackRepository {
                override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
                    val id = target.fileId.value
                    return PlaybackRepositoryResult.Success(
                        PlaybackResolution.Ready(
                            source(folder.savedSeconds[id] ?: 0.0, fileId = id),
                            useStartFrom = true,
                        ),
                    )
                }

                override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult {
                    lookups += target.fileId.value
                    return folder.next[target.fileId.value]?.let(PlaybackNextResult::Found) ?: PlaybackNextResult.Ended
                }
            },
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackRoute(
                    controller = controller,
                    onExit = onExit,
                    onSessionRejected = {},
                    playerFactory = { _, _ -> FakePlayer().also { players += it } },
                    reporter = reporting,
                    autoplayNextVideo = autoplay,
                )
            }
        }
        compose.waitForIdle()
        return lookups
    }

    /** [next] maps a video to the one after it; any other video is the folder's last. */
    private class AutoplayFolder(
        val next: Map<Long, PlaybackTarget>,
        val savedSeconds: Map<Long, Double> = emptyMap(),
    )
}
