package io.putdotio.android

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.playbackState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.Closeable
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import io.putdotio.android.playback.AudioTrackIdentity
import io.putdotio.android.playback.playbackAudioTracks
import io.putdotio.android.playback.DefaultMobilePlayerFactory
import io.putdotio.android.playback.MOBILE_PLAYER_GESTURE_TAG
import io.putdotio.android.playback.MOBILE_NOW_PLAYING_SHEET_TAG
import io.putdotio.android.playback.MOBILE_PLAYER_SIDE_SHEET_TAG
import io.putdotio.android.playback.languageName
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerScreen

/** Real local media and production players; lifecycle and saved-state transitions use a controlled host. */
@RunWith(AndroidJUnit4::class)
class MobilePlaybackOptionsProofTest {
    // Media3 listener coroutines must stay on the main looper; v2's queued dispatcher drains on the test thread.
    private val compose = createAndroidComposeRule<MobileFullscreenProofActivity>()
    private var proofMediaType = PlaybackMediaType.VIDEO
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Playback options proof requires local fixtures and opt-in",
                    arguments.getString("putio.playback.options.enabled") == "true")
                UUID.fromString(requireNotNull(arguments.getString("putio.playback.options.runId")))
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun videoOptionsSurvivePlayerAndSavedStateRecreation() {
        val factory = ObservedPlaybackFactory()
        val lifecycle = OptionsProofLifecycle()
        val restoration = mount(PlaybackMediaType.VIDEO, factory, lifecycle)
        awaitReady(factory)
        val identity = selectOptions(factory, "video")
        val original = compose.runOnIdle { factory.current() }
        compose.runOnIdle { lifecycle.moveTo(Lifecycle.State.CREATED) }
        compose.waitForIdle()
        compose.runOnIdle { lifecycle.moveTo(Lifecycle.State.RESUMED) }
        awaitReady(factory)
        compose.runOnIdle { assertNotSame(original, factory.current()) }
        awaitSelection(factory, identity)
        restoration.emulateSavedInstanceStateRestore()
        awaitReady(factory)
        awaitSelection(factory, identity)
        showOptions()
        screenshot("video-restored")
    }

    @Test
    fun audioOptionsSurviveBackgroundAndSessionReconnection() {
        val factory = ObservedPlaybackFactory()
        val lifecycle = OptionsProofLifecycle()
        val restoration = mount(PlaybackMediaType.AUDIO, factory, lifecycle)
        try {
            awaitReady(factory)
            val identity = selectOptions(factory, "audio")
            val position = compose.runOnIdle {
                lifecycle.moveTo(Lifecycle.State.CREATED)
                factory.current().currentPosition
            }
            compose.waitUntil(15_000) {
                compose.runOnIdle { factory.current().currentPosition > position + 500 }
            }
            compose.runOnIdle {
                assertTrue(factory.current().isPlaying)
                lifecycle.moveTo(Lifecycle.State.RESUMED)
            }
            awaitSelection(factory, identity)
            restoration.emulateSavedInstanceStateRestore()
            awaitReady(factory)
            awaitSelection(factory, identity)
            showOptions()
            screenshot("audio-reconnected")
        } finally {
            compose.runOnIdle { DefaultMobilePlayerFactory.stopAudio(context) }
        }
    }

    private fun mount(
        mediaType: PlaybackMediaType,
        factory: ObservedPlaybackFactory,
        lifecycle: OptionsProofLifecycle,
    ): StateRestorationTester {
        proofMediaType = mediaType
        val source = fixture(mediaType)
        val state = playbackState(
            target = PlaybackTarget(
                FilesItemId(source.fileId),
                if (mediaType == PlaybackMediaType.AUDIO) {
                    "ambient-session-03.m4a"
                } else {
                    "Concert.Rehearsal.Cam2.1080p.mp4"
                },
                mediaType,
            ),
            content = PlaybackContent.Ready(source),
            nextRequestValue = 1,
        )
        compose.runOnUiThread { lifecycle.moveTo(Lifecycle.State.RESUMED) }
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                PutioTheme {
                    Surface(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                            MobilePlayerScreen(
                                state = state,
                                onRetry = { error("Unexpected playback retry") },
                                onPlayerFailure = { failure, _ -> error("Local playback failed: $failure") },
                                onBack = {},
                                playerFactory = factory,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun selectOptions(factory: ObservedPlaybackFactory, label: String): AudioTrackIdentity {
        compose.waitUntil(15_000) {
            compose.runOnIdle { factory.current().currentPosition > 500 && (label == "audio" || factory.renderedFrame) }
        }
        showControls()
        screenshot("$label-player")
        selectSpeedWithKeyboard(factory)
        closeVideoSheet()
        openSpeedOptions()
        compose.onNodeWithText("1.5×").performTouchInput { click() }
        closeVideoSheet()
        val track = compose.runOnIdle { factory.current().currentTracks.playbackAudioTracks()[1] }
        // Both players' audio sheets name a track by its language in its own words.
        val title = languageName(track.identity.language)
            ?: track.label ?: context.getString(R.string.mobile_playback_audio_track_number, 2)
        openAudioOptions()
        compose.onNodeWithText(title).performScrollTo().performTouchInput { click() }
        awaitSelection(factory, track.identity)
        showOptions()
        screenshot("$label-settings")
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        screenshot("$label-selected")
        compose.onNodeWithText(title).performTouchInput { click() }
        return track.identity
    }

    private fun selectSpeedWithKeyboard(factory: ObservedPlaybackFactory) {
        openSpeedOptions()
        val choice = compose.onNodeWithText("1.25×")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Focus opens on the speed track, which takes the arrow keys; Tab moves on to the quick speeds.
        for (ignored in 0 until 12) {
            if (focused("1.25×")) break
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            compose.waitForIdle()
        }
        choice.assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(15_000) {
            compose.runOnIdle { factory.current().playbackParameters.speed == 1.25f }
        }
    }

    private fun focused(label: String) = compose.onAllNodesWithText(label).fetchSemanticsNodes().any {
        it.config.getOrElse(SemanticsProperties.Focused) { false }
    }

    /** The audio choices: the video's Audio side sheet, or Now Playing's sheet under More. */
    private fun showOptions() {
        showControls()
        if (proofMediaType == PlaybackMediaType.VIDEO) {
            tapThroughAndroid(compose.onNodeWithContentDescription("Audio: ", substring = true))
            compose.onNodeWithTag(MOBILE_PLAYER_SIDE_SHEET_TAG).assertIsDisplayed()
        } else {
            tapThroughAndroid(compose.onNodeWithContentDescription("More options"))
            compose.onNodeWithText("Audio track").performTouchInput { click() }
            compose.onNodeWithTag(MOBILE_NOW_PLAYING_SHEET_TAG).assertIsDisplayed()
        }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }

    private fun openSpeedOptions() {
        showControls()
        tapThroughAndroid(compose.onNodeWithContentDescription("Playback speed ", substring = true))
    }

    private fun openAudioOptions() {
        showOptions()
    }

    /** The speed sheet stays open for speed choices, and the chips return once it closes. */
    private fun closeVideoSheet() {
        if (proofMediaType == PlaybackMediaType.VIDEO) {
            tapThroughAndroid(compose.onNodeWithContentDescription("Close"))
        } else {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(MOBILE_PLAYER_SIDE_SHEET_TAG).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag(MOBILE_NOW_PLAYING_SHEET_TAG).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun tapThroughAndroid(node: SemanticsNodeInteraction) {
        val center = node.fetchSemanticsNode().boundsInWindow.center
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        // Inject through Android so the keyboard-to-touch transition also clears native tooltip focus.
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, center.x, center.y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
    }

    private fun showControls() {
        val trigger = if (proofMediaType == PlaybackMediaType.VIDEO) {
            hasContentDescription("Audio: ", substring = true)
        } else {
            hasContentDescription("Playback speed ", substring = true)
        }
        if (compose.onAllNodes(trigger).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG).performTouchInput { click() }
        }
        compose.onNode(trigger).assertIsDisplayed()
    }

    private fun awaitReady(factory: ObservedPlaybackFactory) {
        compose.waitUntil(30_000) {
            compose.runOnIdle {
                factory.player?.let {
                    check(it.playerError == null) { "Local player failed: ${it.playerError?.errorCodeName}" }
                    it.playbackState == Player.STATE_READY && it.currentTracks.playbackAudioTracks().size == 2
                } == true
            }
        }
    }

    private fun awaitSelection(factory: ObservedPlaybackFactory, identity: AudioTrackIdentity) {
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                val player = factory.current()
                player.playbackParameters.speed == 1.5f &&
                    player.currentTracks.playbackAudioTracks().any { it.identity == identity && it.selected }
            }
        }
        compose.runOnIdle { assertEquals(1.5f, factory.current().playbackParameters.speed, 0f) }
    }

    private fun fixture(mediaType: PlaybackMediaType): PlaybackSource {
        val path = requireNotNull(arguments.getString("putio.playback.options.${mediaType.name.lowercase()}"))
        val file = File(path).canonicalFile
        require(file.isFile && file.canRead()) { "Local two-track fixture must be readable" }
        require(file.toPath().startsWith(requireNotNull(context.getExternalFilesDir(null)).canonicalFile.toPath()))
        // Production credential URLs are SDK-owned; this isolated proof substitutes a caller-owned local file.
        val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(android.net.Uri.fromFile(file).toString())
        return PlaybackSource(
            fileId = if (mediaType == PlaybackMediaType.AUDIO) 9_146_001 else 9_146_002,
            kind = PlaybackSourceKind.ORIGINAL,
            url = url,
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )
    }

    private fun screenshot(label: String) {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val runId = UUID.fromString(requireNotNull(arguments.getString("putio.playback.options.runId")))
        saveProofScreenshot(proofDirectory("playback-options-proof-$runId"), label)
    }

    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
}

private class ObservedPlaybackFactory : MobilePlayerFactory {
    var player: Player? = null
        private set
    var renderedFrame = false
        private set
    fun current(): Player = checkNotNull(player)
    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        DefaultMobilePlayerFactory.create(context, mediaType).also {
            player = it
            renderedFrame = false
            it.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { renderedFrame = true }
            })
        }
    override fun connectAudio(context: Context, onResult: (Result<Player>) -> Unit): Closeable =
        DefaultMobilePlayerFactory.connectAudio(context) { result ->
            player = result.getOrThrow()
            onResult(result)
        }
    override fun stopAudio(context: Context) = DefaultMobilePlayerFactory.stopAudio(context)
}

private class OptionsProofLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = registry
    fun moveTo(state: Lifecycle.State) { registry.currentState = state }
}
