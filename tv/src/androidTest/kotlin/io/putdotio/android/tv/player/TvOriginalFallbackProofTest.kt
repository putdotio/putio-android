package io.putdotio.android.tv.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.tv.TvProofActivity
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Opt-in, no API calls: a local original the emulator cannot decode resumes, is rejected by the
 * real Media3 player, and the converted fixture plays on from the same position.
 *
 * Arguments: `putio.tv.fallback.enabled=true`, `putio.tv.fallback.runId` (UUID),
 * `putio.tv.fallback.original` (an undecodable MKV) and `putio.tv.fallback.converted` (an H.264
 * MP4), both under the app's external files directory.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvOriginalFallbackProofTest {
    private val compose = createAndroidComposeRule<TvProofActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(
                    "TV fallback proof requires opt-in",
                    arguments.getString("putio.tv.fallback.enabled") == "true",
                )
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val asked = mutableListOf<Boolean>()
    private val states = mutableListOf<PlaybackState>()

    @After
    fun close() {
        scope.cancel()
    }

    @Test
    fun anOriginalTheTvCannotDecodePlaysConvertedFromWhereItResumed() {
        val factory = FallbackPlayerFactory()
        val controller = compose.runOnUiThread {
            PlaybackController(Target, LocalFixtures(), scope).also { controller ->
                scope.launch { controller.state.collect(states::add) }
            }
        }
        compose.setContent {
            PutioTvTheme {
                TvPlaybackRoute(controller = controller, onExit = {}, onSessionRejected = {}, playerFactory = factory)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(RESUME_TITLE).fetchSemanticsNodes().isNotEmpty() }
        screenshot("01-resume-original")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)

        compose.waitUntil(60_000) {
            compose.runOnIdle {
                val player = factory.players.lastOrNull()
                controller.state.value.readyKind() == PlaybackSourceKind.MP4 && player != null &&
                    factory.players.size == 2 && player.isPlaying && factory.renderedFrame &&
                    player.currentPosition >= SAVED_MILLIS
            }
        }
        screenshot("02-converted-playing")
        compose.runOnIdle {
            val kinds = states.mapNotNull { it.readyKind() }.distinct()
            assertEquals(listOf(PlaybackSourceKind.ORIGINAL, PlaybackSourceKind.MP4), kinds)
            assertEquals(listOf(false, true), asked)
            val converted = factory.players.last()
            assertTrue(
                "Converted starts where the original was: ${converted.currentPosition} ms",
                converted.currentPosition in SAVED_MILLIS..SAVED_MILLIS + DRIFT_MILLIS,
            )
            assertTrue(states.none { it.content is PlaybackContent.Failed })
        }
        controller.close()
    }

    private inner class LocalFixtures : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> {
            asked += convertedOnly
            val source = if (convertedOnly) {
                localSource("putio.tv.fallback.converted", PlaybackSourceKind.MP4)
            } else {
                localSource("putio.tv.fallback.original", PlaybackSourceKind.ORIGINAL)
            }
            return PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source, useStartFrom = true))
        }

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
    }

    private fun localSource(argument: String, kind: PlaybackSourceKind): PlaybackSource {
        val file = File(requireNotNull(arguments.getString(argument)) { "Missing $argument" }).canonicalFile
        require(file.toPath().startsWith(requireNotNull(context.getExternalFilesDir(null)).canonicalFile.toPath()))
        require(file.isFile && file.canRead()) { "Fixture is not readable: $file" }
        return PlaybackSource(
            fileId = Target.fileId.value,
            kind = kind,
            url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
                .newInstance(Uri.fromFile(file).toString()),
            startFromSeconds = SAVED_MILLIS / 1_000.0,
            subtitles = PlaybackSubtitles.None,
        )
    }

    private fun PlaybackState.readyKind(): PlaybackSourceKind? = (content as? PlaybackContent.Ready)?.source?.kind

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-fallback-proof-${runId()}")
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

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.fallback.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        val Target = PlaybackTarget(FilesItemId(9_340_101L), "hevc10.mkv", PlaybackMediaType.VIDEO, 90.0)
        const val SAVED_MILLIS = 30_000L
        const val DRIFT_MILLIS = 10_000L
        const val RESUME_TITLE = "Resume from 0:30?"
    }
}

@UnstableApi
private class FallbackPlayerFactory : TvPlayerFactory {
    val players = mutableListOf<Player>()
    var renderedFrame = false
        private set

    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        DefaultTvPlayerFactory.create(context, mediaType).also { created ->
            players += created
            renderedFrame = false
            created.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    renderedFrame = true
                }
            })
        }

    override fun publish(context: Context, player: Player): java.io.Closeable =
        DefaultTvPlayerFactory.publish(context, player)
}
