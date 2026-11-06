package io.putdotio.android.tv.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.DataStoreSwitchedToConvertedFiles
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
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
import kotlinx.coroutines.runBlocking
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
 * Opt-in, no API calls: the viewer switches a playing original to converted with the D-pad, the
 * converted fixture plays on from that position, and the device's switch list opens the file
 * converted next time. Local fixtures stand in for put.io; the list is the app's own DataStore,
 * emptied before and after.
 *
 * Arguments: `putio.tv.switch.enabled=true`, `putio.tv.switch.runId` (UUID),
 * `putio.tv.switch.original` and `putio.tv.switch.converted` (H.264 MP4s), both under the app's
 * external files directory.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvSwitchToConvertedProofTest {
    private val compose = createAndroidComposeRule<TvProofActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(
                    "TV switch proof requires opt-in",
                    arguments.getString("putio.tv.switch.enabled") == "true",
                )
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val switched by lazy { DataStoreSwitchedToConvertedFiles.get(context) }
    private val asked = mutableListOf<Boolean>()

    @After
    fun close() {
        switched.clear()
        scope.cancel()
    }

    @Test
    fun theViewerSwitchesAnOriginalToConvertedAndTheFileOpensConvertedAgain() {
        switched.clear()
        val factory = RecordingPlayerFactory()
        val first = compose.runOnUiThread { PlaybackController(Target, LocalFixtures(), scope) }
        var open by mutableStateOf(first)
        compose.setContent {
            PutioTvTheme {
                TvPlaybackRoute(controller = open, onExit = {}, onSessionRejected = {}, playerFactory = factory)
            }
        }
        compose.waitUntil(30_000) {
            compose.runOnIdle {
                first.readyKind() == PlaybackSourceKind.ORIGINAL && factory.playing(atLeastMillis = SWITCH_AT_MILLIS)
            }
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(MAX_CHIP_STEPS) { if (!streamChipFocused()) press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertTrue("The Stream chip takes focus", streamChipFocused())
        screenshot("01-stream-chip")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(CONVERTED).fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        screenshot("02-stream-sheet")
        val switchedAt = compose.runOnIdle { factory.players.last().currentPosition }
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        compose.waitUntil(30_000) {
            compose.runOnIdle {
                first.readyKind() == PlaybackSourceKind.MP4 && factory.players.size == 2 && factory.playing()
            }
        }
        screenshot("03-converted-playing")
        compose.runOnIdle {
            val converted = factory.players.last().currentPosition
            assertTrue(
                "Converted plays on from $switchedAt ms, now at $converted ms",
                converted in switchedAt - DRIFT_MILLIS..switchedAt + DRIFT_MILLIS * 2,
            )
            assertTrue(
                "A converted stream offers no switch",
                compose.onAllNodesWithContentDescription(STREAM_CHIP).fetchSemanticsNodes().isEmpty(),
            )
        }
        assertTrue(runBlocking { switched.contains(Target.fileId.value) })

        val again = compose.runOnUiThread { PlaybackController(Target, LocalFixtures(), scope) }
        compose.runOnIdle {
            first.close()
            open = again
        }
        compose.waitUntil(30_000) {
            compose.runOnIdle { again.readyKind() == PlaybackSourceKind.MP4 && factory.playing() }
        }
        screenshot("04-reopened-converted")
        compose.runOnIdle { assertEquals(listOf(false, true, false), asked) }
        again.close()
    }

    private fun streamChipFocused(): Boolean =
        compose.onAllNodesWithContentDescription(STREAM_CHIP).fetchSemanticsNodes()
            .any { it.config.getOrNull(SemanticsProperties.Selected) == true }

    private fun press(vararg keys: Int) {
        keys.forEach {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(it)
            compose.waitForIdle()
        }
    }

    private inner class LocalFixtures : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> {
            asked += convertedOnly
            val source = if (convertedOnly || switched.contains(target.fileId.value)) {
                localSource("putio.tv.switch.converted", PlaybackSourceKind.MP4)
            } else {
                localSource("putio.tv.switch.original", PlaybackSourceKind.ORIGINAL)
            }
            return PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source))
        }

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended

        override fun rememberSwitchedToConverted(target: PlaybackTarget) {
            switched.add(target.fileId.value)
        }
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
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )
    }

    private fun PlaybackController.readyKind(): PlaybackSourceKind? =
        (state.value.content as? PlaybackContent.Ready)?.source?.kind

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-switch-proof-${runId()}")
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

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.switch.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        val Target = PlaybackTarget(FilesItemId(9_340_202L), "switch.mkv", PlaybackMediaType.VIDEO, 90.0)
        const val SWITCH_AT_MILLIS = 8_000L
        const val DRIFT_MILLIS = 3_000L
        const val MAX_CHIP_STEPS = 6
        const val STREAM_CHIP = "Stream: Original"
        const val CONVERTED = "Converted"
    }
}

@UnstableApi
private class RecordingPlayerFactory : TvPlayerFactory {
    val players = mutableListOf<Player>()

    fun playing(atLeastMillis: Long = 0L): Boolean =
        players.lastOrNull()?.let { it.isPlaying && it.currentPosition >= atLeastMillis } == true

    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        DefaultTvPlayerFactory.create(context, mediaType).also { players += it }

    override fun publish(context: Context, player: Player): java.io.Closeable =
        DefaultTvPlayerFactory.publish(context, player)
}
