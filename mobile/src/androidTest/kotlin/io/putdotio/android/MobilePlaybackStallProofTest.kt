package io.putdotio.android

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerScreen
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.StallGate
import io.putdotio.android.playback.StallingDataSourceFactory
import io.putdotio.android.playback.playbackExoPlayer
import io.putdotio.android.playback.playbackLoadControl
import io.putdotio.android.playback.playbackState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.File
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MobilePlaybackStallProofTest {
    private val compose = createAndroidComposeRule<MobileFullscreenProofActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Stall proof requires opt-in", arguments.getString(ENABLED) == "true")
                proofDirectory("playback-stall-proof-${runId()}")
                assumeTrue("Needs a stall fixture", arguments.getString(FIXTURE) != null)
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun aLongRebufferShowsBufferingThenStalledAndTryAgainResumesInPlace() {
        val fixture = proofFixture(FIXTURE)
        val gate = StallGate(stallAfterBytes = fixture.length() * STALL_AT_PERCENT / PERCENT)
        var player: Player? = null
        val factory = MobilePlayerFactory { context, _ ->
            playbackExoPlayer(context, null, StallingDataSourceFactory(DefaultDataSource.Factory(context), gate)) {
                setLoadControl(playbackLoadControl(context).build())
            }.also { player = it }
        }
        compose.setContent {
            PutioTheme {
                Surface(Modifier.fillMaxSize()) {
                    MobilePlayerScreen(
                        state = playbackState(
                            target = PlaybackTarget(FilesItemId(FILE_ID), TITLE, PlaybackMediaType.VIDEO),
                            content = PlaybackContent.Ready(source(fixture)),
                            nextRequestValue = 1,
                        ),
                        onRetry = { error("Unexpected screen retry") },
                        onPlayerFailure = { failure, _ -> error("Local video failed: $failure") },
                        onBack = {},
                        playerFactory = factory,
                    )
                }
            }
        }

        awaitPlayer({ player }) { it.isPlaying }
        awaitPlayer({ player }) { it.playWhenReady && it.playbackState == Player.STATE_BUFFERING }
        val stalledAt = compose.runOnIdle { checkNotNull(player).currentPosition }
        compose.mainClock.autoAdvance = false
        advance(REBUFFER_SHOWN_MILLIS)
        screenshot("stall-01-rebuffer")
        compose.onAllNodesWithText(BUFFERING).assertCountEquals(0)

        advance(BUFFERING_NOTICE_MILLIS - REBUFFER_SHOWN_MILLIS)
        screenshot("stall-02-buffering")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(BUFFERING).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(STALLED).assertCountEquals(0)

        advance(STALLED_MILLIS - BUFFERING_NOTICE_MILLIS)
        screenshot("stall-03-playback-stalled")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(STALLED).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(TRY_AGAIN).assertIsDisplayed()
        compose.onNodeWithContentDescription(BACK).assertIsDisplayed()

        compose.onNodeWithText(TRY_AGAIN).performClick()
        gate.release()
        compose.mainClock.autoAdvance = true
        awaitPlayer({ player }) { it.isPlaying }
        val resumedAt = compose.runOnIdle { checkNotNull(player).currentPosition }
        assertTrue(
            "Try again resumed at $resumedAt ms after stalling at $stalledAt ms",
            resumedAt in (stalledAt - POSITION_SLACK_MILLIS)..(stalledAt + POSITION_SLACK_MILLIS),
        )
        compose.onAllNodesWithText(STALLED).assertCountEquals(0)
        screenshot("stall-04-resumed")
    }

    private fun source(file: File): PlaybackSource {
        val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(Uri.fromFile(file).toString())
        return PlaybackSource(
            fileId = FILE_ID,
            kind = PlaybackSourceKind.ORIGINAL,
            url = url,
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )
    }

    private fun advance(millis: Long) {
        var remaining = millis
        while (remaining > 0) {
            val step = minOf(STEP_MILLIS, remaining)
            compose.mainClock.advanceTimeBy(step)
            remaining -= step
        }
    }

    private fun awaitPlayer(player: () -> Player?, predicate: (Player) -> Boolean) {
        compose.waitUntil(PLAYER_TIMEOUT_MILLIS) {
            compose.runOnIdle {
                player()?.let {
                    check(it.playerError == null) { "Playback failed: ${it.playerError?.errorCodeName}" }
                    predicate(it)
                } == true
            }
        }
    }

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        saveProofScreenshot(proofDirectory("playback-stall-proof-${runId()}"), label)
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString(RUN_ID)))
    private val arguments get() = InstrumentationRegistry.getArguments()

    private companion object {
        const val ENABLED = "putio.playback.stall.enabled"
        const val FIXTURE = "putio.playback.stall.fixture"
        const val RUN_ID = "putio.playback.stall.runId"
        const val FILE_ID = 9_154_101L
        const val TITLE = "Stall proof.mp4"
        const val BUFFERING = "Buffering…"
        const val STALLED = "Playback stalled"
        const val TRY_AGAIN = "Try again"
        const val BACK = "Back"
        const val STALL_AT_PERCENT = 30L
        const val PERCENT = 100L
        const val REBUFFER_SHOWN_MILLIS = 1_000L
        const val BUFFERING_NOTICE_MILLIS = 10_000L
        const val STALLED_MILLIS = 30_000L
        const val STEP_MILLIS = 100L
        const val PLAYER_TIMEOUT_MILLIS = 120_000L
        const val POSITION_SLACK_MILLIS = 3_000L
    }
}
