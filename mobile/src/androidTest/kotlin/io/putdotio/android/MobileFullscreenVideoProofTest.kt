package io.putdotio.android

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.AudioTrackIdentity
import io.putdotio.android.playback.MOBILE_PLAYER_GESTURE_TAG
import io.putdotio.android.playback.MOBILE_PLAYER_SIDE_SHEET_TAG
import io.putdotio.android.playback.PlaybackAudioTrack
import io.putdotio.android.playback.PlaybackSubtitleTrack
import io.putdotio.android.playback.languageName
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerScreen
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SubtitleTrackIdentity
import io.putdotio.android.playback.audioAttributes
import io.putdotio.android.playback.playbackAudioTracks
import io.putdotio.android.playback.playbackRenderersFactory
import io.putdotio.android.playback.playbackState
import io.putdotio.android.playback.playbackSubtitleTracks
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Real video, tracks, cues, window rotation and insets; saved-state recreation uses the Compose test harness. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MobileFullscreenVideoProofTest {
    private val compose = createAndroidComposeRule<MobileFullscreenProofActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Fullscreen video proof requires opt-in",
                    arguments.getString("putio.video.fullscreen.enabled") == "true")
                require(Build.VERSION.SDK_INT == 37) { "Fullscreen video proof requires API 37" }
                runId()
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun landscapePlaybackUsesDirectControlsAndRestoresThePreviousWindow() {
        val factory = FullscreenProofPlayerFactory()
        val state = localVideoState()
        var showingVideo by mutableStateOf(false)
        showBars(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LocalVideoHost(showingVideo, state, factory, onBack = { showingVideo = false })
        }
        awaitWindow(Configuration.ORIENTATION_PORTRAIT, barsVisible = true)
        compose.runOnIdle { showingVideo = true }
        try {
            awaitVideo(factory)
            awaitWindow(Configuration.ORIENTATION_LANDSCAPE, barsVisible = false)
            showControls()
            compose.onNodeWithContentDescription("Playback speed 1×").assertIsDisplayed()
            screenshot("landscape-direct-controls")
            compose.onNodeWithContentDescription("Playback speed 1×").performTouchInput { click() }
            screenshot("landscape-speed-sheet")
            compose.onNode(hasText("1.5×") and isSelectable()).performScrollTo().performTouchInput { click() }
            closeSheet()
            val audio = compose.runOnIdle { factory.current().currentTracks.playbackAudioTracks()[1] }
            showControls()
            compose.onNodeWithContentDescription("Audio: ", substring = true).performTouchInput { click() }
            screenshot("landscape-audio-sheet")
            compose.onNode(hasText(audio.rowName()) and isSelectable()).performScrollTo().performTouchInput { click() }
            val caption = compose.runOnIdle { factory.current().currentTracks.playbackSubtitleTracks().single() }
            chooseCaption(caption.rowName(), captureSheet = true)
            awaitChoices(factory, audio.identity, caption.identity)
            showControls()
            screenshot("landscape-selected-caption")
            val originalPlayer = compose.runOnIdle {
                factory.current().also { player ->
                    player.pause()
                    player.seekTo(45_000L)
                }
            }
            awaitPlayer(factory) { it.playbackState == Player.STATE_READY && it.currentPosition >= 45_000L }
            val positionBeforeRestore = compose.runOnIdle { originalPlayer.currentPosition }
            // Android pauses the Activity before saving it; playback snapshots its live position there.
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            restoration.emulateSavedInstanceStateRestore()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            awaitVideo(factory)
            compose.runOnIdle { assertNotSame(originalPlayer, factory.current()) }
            awaitChoices(factory, audio.identity, caption.identity)
            compose.runOnIdle {
                val restored = factory.current()
                assertTrue("Playback position must survive replacement",
                    restored.currentPosition in positionBeforeRestore..positionBeforeRestore + 1_500L)
                assertFalse("Paused playback must remain paused", restored.playWhenReady)
                restored.play()
            }
            awaitWindow(Configuration.ORIENTATION_LANDSCAPE, barsVisible = false)
            screenshot("landscape-restored")
            chooseCaption("Off")
            awaitPlayer(factory) { it.currentCues.cues.isEmpty() }
            chooseCaption(caption.rowName())
            awaitPlayer(factory) { it.currentCues.cues.any { cue -> cue.text?.contains(CAPTION_TEXT) == true } }
            screenshot("landscape-chosen-caption")
            showControls()
            compose.onNodeWithContentDescription("Back").performTouchInput { click() }
            compose.onNodeWithText("Local video proof").assertIsDisplayed()
            awaitWindow(Configuration.ORIENTATION_PORTRAIT, barsVisible = true)
            compose.runOnIdle {
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, compose.activity.requestedOrientation)
            }
            screenshot("restored-portrait-window")
        } finally {
            compose.runOnUiThread { showingVideo = false }
        }
    }

    @Test
    fun portraitVideoKeepsAnUnlockedPortraitWindow() {
        val fixture = arguments.getString("putio.video.fullscreen.portraitFixture")
        assumeTrue("Needs a portrait fixture", fixture != null)
        val factory = FullscreenProofPlayerFactory()
        val state = localVideoState(checkNotNull(fixture))
        var showingVideo by mutableStateOf(false)
        showBars(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        compose.setContent {
            LocalVideoHost(showingVideo, state, factory, onBack = { showingVideo = false })
        }
        awaitWindow(Configuration.ORIENTATION_PORTRAIT, barsVisible = true)
        compose.runOnIdle { showingVideo = true }
        try {
            awaitPlayer(factory) {
                it.playbackState == Player.STATE_READY && it.videoSize.height > it.videoSize.width &&
                    it.currentPosition > 500 && factory.renderedFrame
            }
            awaitWindow(Configuration.ORIENTATION_PORTRAIT, barsVisible = false)
            // Give a landscape request time to land before checking that none did.
            compose.mainClock.advanceTimeBy(1_000)
            Thread.sleep(1_000)
            compose.runOnIdle {
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
                assertEquals(Configuration.ORIENTATION_PORTRAIT, compose.activity.resources.configuration.orientation)
            }
            screenshot("portrait-video-portrait-window")
            showControls(captions = false)
            screenshot("portrait-video-controls")
            compose.onNodeWithContentDescription("Back").performTouchInput { click() }
            compose.onNodeWithText("Local video proof").assertIsDisplayed()
            awaitWindow(Configuration.ORIENTATION_PORTRAIT, barsVisible = true)
            compose.runOnIdle {
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
            }
        } finally {
            compose.runOnUiThread { showingVideo = false }
        }
    }

    private fun showBars(requestedOrientation: Int) = compose.runOnUiThread {
        compose.activity.requestedOrientation = requestedOrientation
        val window = compose.activity.window
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    @Composable
    private fun LocalVideoHost(
        showingVideo: Boolean,
        state: PlaybackState,
        factory: FullscreenProofPlayerFactory,
        onBack: () -> Unit,
    ) {
        PutioTheme {
            Surface(Modifier.fillMaxSize()) {
                if (showingVideo) {
                    MobilePlayerScreen(
                        state = state,
                        onRetry = { error("Unexpected local video retry") },
                        onPlayerFailure = { failure, _ -> error("Local video failed: $failure") },
                        onBack = onBack,
                        playerFactory = factory,
                    )
                } else {
                    Text("Local video proof")
                }
            }
        }
    }

    private fun chooseCaption(label: String, captureSheet: Boolean = false) {
        showControls()
        compose.onNodeWithContentDescription("Subtitles: ", substring = true).performTouchInput { click() }
        if (captureSheet) screenshot("landscape-captions-sheet")
        compose.onNode(hasText(label) and isSelectable()).performScrollTo().performTouchInput { click() }
    }

    private fun closeSheet() {
        compose.onNodeWithContentDescription("Close").performTouchInput { click() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(MOBILE_PLAYER_SIDE_SHEET_TAG).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun showControls(captions: Boolean = true) {
        val probe = hasContentDescription("Playback speed ", substring = true)
        if (compose.onAllNodes(probe).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG).performTouchInput { click() }
        }
        if (captions) {
            compose.onNodeWithContentDescription("Audio: ", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Subtitles: ", substring = true).assertIsDisplayed()
        }
        compose.onNode(probe).assertIsDisplayed()
    }

    /** How the side sheet names a track: its language in its own words, else its label. */
    private fun PlaybackAudioTrack.rowName(): String = languageName(identity.language) ?: checkNotNull(label)
    private fun PlaybackSubtitleTrack.rowName(): String = languageName(identity.language) ?: checkNotNull(label)

    private fun awaitVideo(factory: FullscreenProofPlayerFactory) {
        awaitPlayer(factory) {
            it.playbackState == Player.STATE_READY && it.videoSize.width > it.videoSize.height &&
                it.currentPosition > 500 && factory.renderedFrame &&
                it.currentTracks.playbackAudioTracks().size == 2 && it.currentTracks.playbackSubtitleTracks().size == 1
        }
    }

    private fun awaitChoices(
        factory: FullscreenProofPlayerFactory,
        audio: AudioTrackIdentity,
        caption: SubtitleTrackIdentity,
    ) {
        awaitPlayer(factory) { player ->
            player.playbackParameters.speed == 1.5f &&
                player.currentTracks.playbackAudioTracks().any { it.identity == audio && it.selected } &&
                player.currentTracks.playbackSubtitleTracks().any { it.identity == caption && it.selected } &&
                player.currentCues.cues.any { it.text?.contains(CAPTION_TEXT) == true }
        }
    }

    private fun awaitPlayer(factory: FullscreenProofPlayerFactory, predicate: (Player) -> Boolean) {
        compose.waitUntil(30_000) {
            compose.runOnIdle {
                factory.player?.let {
                    check(it.playerError == null) { "Video failed: ${it.playerError?.errorCodeName}" }
                    predicate(it)
                } == true
            }
        }
    }

    private fun awaitWindow(orientation: Int, barsVisible: Boolean) {
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                val activity = compose.activity
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                activity.resources.configuration.orientation == orientation && insets != null &&
                    insets.isVisible(WindowInsetsCompat.Type.statusBars()) == barsVisible &&
                    insets.isVisible(WindowInsetsCompat.Type.navigationBars()) == barsVisible
            }
        }
    }

    private fun localVideoState(
        path: String = requireNotNull(arguments.getString("putio.video.fullscreen.fixture")),
    ): PlaybackState {
        val file = File(path).canonicalFile
        require(file.isFile && file.canRead())
        require(file.toPath().startsWith(requireNotNull(context.getExternalFilesDir(null)).canonicalFile.toPath()))
        // The SDK owns production URLs. This proof reads only its caller-owned local fixture.
        val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(Uri.fromFile(file).toString())
        return playbackState(
            target = PlaybackTarget(
                FilesItemId(9_154_001), file.name, PlaybackMediaType.VIDEO,
            ),
            content = PlaybackContent.Ready(PlaybackSource(
                fileId = 9_154_001,
                kind = PlaybackSourceKind.ORIGINAL,
                url = url,
                startFromSeconds = 0.0,
                subtitles = PlaybackSubtitles.None,
            )),
            nextRequestValue = 1,
        )
    }

    private fun screenshot(label: String) {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        saveProofScreenshot(proofDirectory("fullscreen-video-proof-${runId()}"), label)
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.video.fullscreen.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val CAPTION_TEXT = "Rehearsal caption proof"
    }
}

private class FullscreenProofPlayerFactory : MobilePlayerFactory {
    var player: Player? = null
        private set
    var renderedFrame = false
        private set
    fun current(): Player = checkNotNull(player)
    // The production player streams through the download cache's HTTP source, which cannot read
    // the caller's local file; this one keeps its renderers and audio attributes.
    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        ExoPlayer.Builder(context, playbackRenderersFactory(context))
            .setAudioAttributes(mediaType.audioAttributes(), true)
            .build()
            .also {
                player = it
                renderedFrame = false
                it.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { renderedFrame = true }
                })
            }
    // This private-video proof must not stop an unrelated real audio session.
    override fun stopAudio(context: Context) = Unit
}
