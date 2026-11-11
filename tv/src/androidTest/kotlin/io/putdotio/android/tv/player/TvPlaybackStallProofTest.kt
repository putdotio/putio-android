package io.putdotio.android.tv.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.StallGate
import io.putdotio.android.playback.StallingDataSourceFactory
import io.putdotio.android.playback.playbackExoPlayer
import io.putdotio.android.playback.playbackState
import io.putdotio.android.tv.TvProofActivity
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
class TvPlaybackStallProofTest {
    private val compose = createAndroidComposeRule<TvProofActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("TV player proof requires opt-in", arguments.getString(ENABLED) == "true")
                context.getExternalFilesDir(null)
                assumeTrue("Needs a stall fixture", arguments.getString(FIXTURE) != null)
                runId()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun aLongRebufferShowsBufferingThenStalledAndTryAgainResumesInPlace() {
        val fixture = File(requireNotNull(arguments.getString(FIXTURE))).canonicalFile
        require(fixture.toPath().startsWith(requireNotNull(context.getExternalFilesDir(null)).canonicalFile.toPath()))
        val gate = StallGate(stallAfterBytes = fixture.length() * STALL_AT_PERCENT / PERCENT)
        var player: Player? = null
        val factory = object : TvPlayerFactory {
            override fun create(context: Context, mediaType: PlaybackMediaType): Player =
                playbackExoPlayer(context, null, StallingDataSourceFactory(DefaultDataSource.Factory(context), gate)) {
                    setLoadControl(tvLoadControl(context))
                }.also { player = it }

            override fun publish(context: Context, player: Player): java.io.Closeable =
                DefaultTvPlayerFactory.publish(context, player)
        }
        compose.setContent {
            PutioTvTheme {
                TvPlayerScreen(
                    state = playbackState(
                        target = PlaybackTarget(FilesItemId(FILE_ID), TITLE, PlaybackMediaType.VIDEO),
                        content = PlaybackContent.Ready(source(fixture)),
                        nextRequestValue = 2L,
                    ),
                    onBack = {},
                    onRetry = {},
                    onPlayerFailure = { failure, _ -> error("Local playback failed: $failure") },
                    playerFactory = factory,
                    autoplayNextVideo = false,
                    onPlaybackEnded = { true },
                    onOpenInVlc = null,
                    onPlayInVlc = null,
                )
            }
        }

        awaitPlayer({ player }) { it.isPlaying }
        awaitPlayer({ player }) { it.playWhenReady && it.playbackState == Player.STATE_BUFFERING }
        val stalledAt = compose.runOnIdle { checkNotNull(player).currentPosition }
        compose.mainClock.autoAdvance = false
        advance(REBUFFER_SHOWN_MILLIS)
        screenshot("stall-01-rebuffer-spinner")
        compose.onAllNodesWithText(BUFFERING).assertCountEquals(0)

        advance(BUFFERING_NOTICE_MILLIS - REBUFFER_SHOWN_MILLIS)
        screenshot("stall-02-buffering")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(BUFFERING).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(STALLED).assertCountEquals(0)

        advance(STALLED_MILLIS - BUFFERING_NOTICE_MILLIS + FOCUS_SETTLE_MILLIS)
        screenshot("stall-03-playback-stalled")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(STALLED).fetchSemanticsNodes().isNotEmpty() }
        awaitFocused(TRY_AGAIN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocused(GO_BACK)
        advance(FOCUS_SETTLE_MILLIS)
        screenshot("stall-04-go-back")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocused(TRY_AGAIN)

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        gate.release()
        compose.mainClock.autoAdvance = true
        awaitPlayer({ player }) { it.isPlaying }
        val resumedAt = compose.runOnIdle { checkNotNull(player).currentPosition }
        assertTrue(
            "Try again resumed at $resumedAt ms after stalling at $stalledAt ms",
            resumedAt in (stalledAt - POSITION_SLACK_MILLIS)..(stalledAt + POSITION_SLACK_MILLIS),
        )
        compose.onAllNodesWithText(STALLED).assertCountEquals(0)
        screenshot("stall-05-resumed")
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

    private fun awaitFocused(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Focused) == true
            }
        }
        compose.onNodeWithText(text).assertIsFocused()
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-stall-proof-${runId()}")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString(RUN_ID)))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val ENABLED = "putio.tv.player.enabled"
        const val FIXTURE = "putio.tv.player.stallFixture"
        const val RUN_ID = "putio.tv.player.runId"
        const val FILE_ID = 9_340_101L
        const val TITLE = "Stall proof.mp4"
        const val BUFFERING = "Buffering…"
        const val STALLED = "Playback stalled"
        const val TRY_AGAIN = "Try again"
        const val GO_BACK = "Go back"
        const val STALL_AT_PERCENT = 30L
        const val PERCENT = 100L
        const val REBUFFER_SHOWN_MILLIS = 1_000L
        const val BUFFERING_NOTICE_MILLIS = 10_000L
        const val STALLED_MILLIS = 30_000L
        const val STEP_MILLIS = 100L
        const val FOCUS_SETTLE_MILLIS = 1_000L
        const val PLAYER_TIMEOUT_MILLIS = 120_000L
        const val POSITION_SLACK_MILLIS = 3_000L
    }
}
