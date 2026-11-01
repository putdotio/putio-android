package io.putdotio.android.tv.player

import androidx.activity.ComponentActivity
import io.putdotio.android.tv.setTvContent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PLAYBACK_REPORTING_LEASE_KEY
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.errors.PutioConfigurationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.math.ceil
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

        compose.runOnIdle { players.last().end() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(9L, 10L), lookups)
            assertEquals(1, exits)
        }
        reporting.close()
    }

    @Test
    fun withAutoplayTheEndShowsUpNextAndPlaysTheNextVideoTenSecondsLater() {
        val players = mutableListOf<FakePlayer>()
        var exits = 0
        showAutoplayRoute(
            autoplay = true,
            players = players,
            folder = AutoplayFolder(next = mapOf(9L to NEXT), upNext = true),
            onExit = { exits += 1 },
        )
        compose.mainClock.autoAdvance = false

        compose.runOnIdle { players.single().end() }
        compose.settle()
        compose.onNodeWithTag(TV_UP_NEXT_TAG).assertIsDisplayed()
        compose.onNodeWithText("Up next in Harbor").assertIsDisplayed()
        compose.onNodeWithText("Harbor film 2.mp4").assertIsDisplayed()
        compose.onNodeWithText("Playing in 10").assertIsDisplayed()
        compose.onNodeWithText("Play now").assertIsFocused()

        compose.mainClock.advanceTimeBy(TV_UP_NEXT_MILLIS / 2)
        compose.onNodeWithText("Playing in 5").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Nothing plays during the countdown", 1, players.size) }

        compose.mainClock.advanceTimeBy(TV_UP_NEXT_MILLIS / 2)
        compose.settle()
        compose.runOnIdle {
            assertEquals(0, exits)
            assertEquals(2, players.size)
            assertEquals("10", players.last().mediaItems.single().mediaId)
        }
        compose.onNodeWithTag(TV_UP_NEXT_TAG).assertDoesNotExist()
    }

    @Test
    fun playNowSkipsTheCountdown() {
        val players = showUpNext()
        compose.onNodeWithText("Play now").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.settle()
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertEquals("10", players.last().mediaItems.single().mediaId)
        }
    }

    @Test
    fun cancelStaysOnTheEndAndGivesTheControlsBackTheirFocus() {
        var exits = 0
        val players = showUpNext(onExit = { exits += 1 })
        compose.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        compose.settle()
        compose.onNodeWithTag(TV_UP_NEXT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        compose.mainClock.advanceTimeBy(TV_UP_NEXT_MILLIS * 2)
        compose.runOnIdle {
            assertEquals(0, exits)
            assertEquals(1, players.size)
        }
    }

    @Test
    fun backCancelsUpNextBeforeItLeaves() {
        var exits = 0
        val players = showUpNext(onExit = { exits += 1 })
        compose.back()
        compose.settle()
        compose.onNodeWithTag(TV_UP_NEXT_TAG).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(TV_UP_NEXT_MILLIS * 2)
        compose.runOnIdle {
            assertEquals(0, exits)
            assertEquals(1, players.size)
        }
    }

    @Test
    fun playNowPlaysTheFileTheCardShows() {
        val elsewhere = PlaybackTarget(FilesItemId(11), "Harbor film 3.mp4")
        val players = showUpNext(
            folder = AutoplayFolder(
                next = mapOf(9L to elsewhere),
                upNext = true,
                upNextFiles = PlaybackRepositoryResult.Success(mapOf(9L to NEXT)),
            ),
        )
        compose.onNodeWithText("Harbor film 2.mp4").assertIsDisplayed()
        compose.onNodeWithText("Play now").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.settle()
        compose.runOnIdle { assertEquals("10", players.last().mediaItems.single().mediaId) }
    }

    @Test
    fun aFailedUpNextLookupStillLooksForTheNextVideoAtTheEnd() {
        val players = mutableListOf<FakePlayer>()
        var exits = 0
        val lookups = showAutoplayRoute(
            autoplay = true,
            players = players,
            folder = AutoplayFolder(
                next = mapOf(9L to NEXT),
                upNext = true,
                upNextFiles = PlaybackRepositoryResult.Failure(
                    PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(PutioConfigurationException("offline"))),
                ),
            ),
            onExit = { exits += 1 },
        )

        compose.runOnIdle { players.single().end() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, exits)
            assertEquals(listOf(9L), lookups)
            assertEquals("10", players.last().mediaItems.single().mediaId)
        }
    }

    private fun showUpNext(
        onExit: () -> Unit = {},
        folder: AutoplayFolder = AutoplayFolder(next = mapOf(9L to NEXT), upNext = true),
    ): List<FakePlayer> {
        val players = mutableListOf<FakePlayer>()
        showAutoplayRoute(
            autoplay = true,
            players = players,
            folder = folder,
            onExit = onExit,
        )
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { players.single().end() }
        compose.settle()
        compose.onNodeWithTag(TV_UP_NEXT_TAG).assertIsDisplayed()
        return players
    }

    @Test
    fun anEndBeforeTheLookupResolvesStillCountsDown() {
        val players = mutableListOf<FakePlayer>()
        val neighbours = CompletableDeferred<Unit>()
        showAutoplayRoute(
            autoplay = true,
            players = players,
            folder = AutoplayFolder(next = mapOf(9L to NEXT), upNext = true, neighbours = neighbours),
        )
        compose.mainClock.autoAdvance = false

        compose.runOnIdle { players.single().end() }
        compose.settle()
        compose.runOnIdle { assertEquals("Nothing plays before the countdown", 1, players.size) }

        compose.runOnIdle { neighbours.complete(Unit) }
        compose.settle()
        compose.onNodeWithText("Playing in 10").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, players.size) }
    }

    @Test
    fun aSeekDuringTheCountdownCancelsUpNext() {
        val players = showUpNext()
        compose.runOnIdle { players.single().seekTo(0L) }
        compose.settle()
        compose.onNodeWithTag(TV_UP_NEXT_TAG).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(TV_UP_NEXT_MILLIS * 2)
        compose.runOnIdle { assertEquals(1, players.size) }
    }

    @Test
    fun aSeekOnTheCountdownsLastFrameStillCancelsUpNext() {
        val players = showUpNext()
        var frames = 0
        while (compose.onAllNodesWithText("Playing in 9").fetchSemanticsNodes().isEmpty()) {
            compose.mainClock.advanceTimeByFrame()
            frames += 1
            assertTrue("The countdown reaches 9", frames < FRAMES_PER_COUNTDOWN)
        }
        val framesLeft = framesFor(TV_UP_NEXT_MILLIS) - framesFor(SECOND_MILLIS)
        repeat(framesLeft - 2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals("Nothing plays before the last frame", 1, players.size) }

        compose.runOnIdle { players.single().seekTo(0L) }
        compose.mainClock.advanceTimeByFrame()
        compose.settle()
        compose.onNodeWithTag(TV_UP_NEXT_TAG).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(TV_UP_NEXT_MILLIS * 2)
        compose.runOnIdle { assertEquals(1, players.size) }
    }

    @Test
    fun upNextNeverOffersAVideoThisSessionAlreadyPlayed() {
        val players = mutableListOf<FakePlayer>()
        var exits = 0
        showAutoplayRoute(
            autoplay = true,
            players = players,
            folder = AutoplayFolder(
                next = mapOf(9L to NEXT, 10L to PlaybackTarget(FilesItemId(9), "Harbor film.mp4")),
                upNext = true,
            ),
            onExit = { exits += 1 },
        )
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { players.single().end() }
        compose.settle()
        compose.mainClock.advanceTimeBy(TV_UP_NEXT_MILLIS)
        compose.settle()
        compose.runOnIdle { assertEquals("10", players.last().mediaItems.single().mediaId) }

        compose.runOnIdle { players.last().end() }
        compose.settle()
        compose.onNodeWithTag(TV_UP_NEXT_TAG).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(TV_UP_NEXT_MILLIS * 2)
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertEquals(1, exits)
        }
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
        compose.onNodeWithText(RESUME_LABEL).assertIsFocused()
        compose.runOnIdle { assertEquals("No player before the choice", 1, players.size) }
    }

    @Test
    fun nextEpisodeFollowsTheAccountsSettingAndPlaysTheFoundVideoWithoutAutoplay() {
        val players = mutableListOf<FakePlayer>()
        var exits = 0
        val lookups = showAutoplayRoute(
            autoplay = false,
            players = players,
            folder = AutoplayFolder(
                next = mapOf(9L to PlaybackTarget(FilesItemId(10), "Harbor film 2.mp4")),
                nextEpisode = true,
            ),
            onExit = { exits += 1 },
        )

        compose.onNodeWithContentDescription(NEXT_EPISODE).assertExists()
        listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight)
            .forEach { key -> compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(key) } }
        compose.onNodeWithContentDescription(NEXT_EPISODE).assertIsSelected()
        compose.onNodeWithTag(TV_PLAYER_TAG).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, exits)
            assertEquals(2, players.size)
            assertEquals("10", players.last().mediaItems.single().mediaId)
        }
        compose.onNodeWithContentDescription(NEXT_EPISODE).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(9L, 10L), lookups) }
    }

    @Test
    fun nextEpisodeStaysOffWithoutTheSettingEvenWithAutoplay() {
        val lookups = showAutoplayRoute(
            autoplay = true,
            players = mutableListOf(),
            folder = AutoplayFolder(next = mapOf(9L to PlaybackTarget(FilesItemId(10), "Harbor film 2.mp4"))),
        )

        compose.onNodeWithContentDescription(NEXT_EPISODE).assertDoesNotExist()
        compose.runOnIdle { assertTrue(lookups.isEmpty()) }
    }

    private fun showAutoplayRoute(
        autoplay: Boolean,
        players: MutableList<FakePlayer>,
        folder: AutoplayFolder,
        reporting: TvPlaybackReporter = TvPlaybackReporter.None,
        onExit: () -> Unit = {},
    ): List<Long> {
        val lookups = mutableListOf<Long>()
        val repository = object : PlaybackRepository {
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

            override suspend fun findNeighbours(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackNeighbours> {
                folder.neighbours.await()
                val files = when (val found = folder.upNextFiles) {
                    null -> folder.next
                    is PlaybackRepositoryResult.Success -> found.value
                    is PlaybackRepositoryResult.Failure -> return found
                }
                return PlaybackRepositoryResult.Success(
                    PlaybackNeighbours(next = files[target.fileId.value], folderName = "Harbor"),
                )
            }
        }
        val controller = PlaybackController(
            PlaybackTarget(FilesItemId(9), "Harbor film.mp4", PlaybackMediaType.VIDEO),
            repository,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        compose.setTvContent {
            TvPlaybackRoute(
                controller = controller,
                onExit = onExit,
                onSessionRejected = {},
                playerFactory = { _, _ -> FakePlayer().also { players += it } },
                reporter = reporting,
                autoplayNextVideo = autoplay,
                findNextEpisode = repository::findNextVideo.takeIf { folder.nextEpisode },
                findUpNext = repository::findNeighbours.takeIf { folder.upNext },
            )
        }
        compose.waitForIdle()
        return lookups
    }

    private class AutoplayFolder(
        val next: Map<Long, PlaybackTarget>,
        val savedSeconds: Map<Long, Double> = emptyMap(),
        val nextEpisode: Boolean = false,
        val upNext: Boolean = false,
        val neighbours: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        val upNextFiles: PlaybackRepositoryResult<Map<Long, PlaybackTarget>>? = null,
    )
}

private const val NEXT_EPISODE = "Next episode"
private const val FRAME_MILLIS = 16L
private const val SECOND_MILLIS = 1_000L
private const val FRAMES_PER_COUNTDOWN = TV_UP_NEXT_MILLIS / FRAME_MILLIS

private fun framesFor(millis: Long): Int = ceil(millis / FRAME_MILLIS.toDouble()).toInt()
private val NEXT = PlaybackTarget(FilesItemId(10), "Harbor film 2.mp4")
