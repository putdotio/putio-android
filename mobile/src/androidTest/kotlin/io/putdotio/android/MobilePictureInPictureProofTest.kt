package io.putdotio.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerScreen
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackPositionObserver
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.audioAttributes
import io.putdotio.android.playback.playbackRenderersFactory
import io.putdotio.android.playback.playbackState
import io.putdotio.android.playback.withReportingLease
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * The real player screen inside MainActivity, so its manifest declarations are the ones on trial,
 * playing a caller-owned local video through Home, the system's picture-in-picture menu and its
 * expand button. Compose runs on the real frame clock, as in the app. It makes no API calls.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MobilePictureInPictureProofTest {
    @get:Rule
    val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(
                    "Picture-in-picture proof requires opt-in",
                    arguments.getString("putio.pip.enabled") == "true",
                )
                require(Build.VERSION.SDK_INT == 37) { "Picture-in-picture proof requires API 37" }
                runId()
                base.evaluate()
            }
        }
    }

    private lateinit var factory: PictureInPictureProofPlayerFactory
    private lateinit var activity: MainActivity

    @Test
    fun homeEntersTheWindowWhichKeepsPlayingObeysItsControlsAndExpandsWherePlaybackIs() {
        val scenario = launchPlayer()
        try {
            awaitPlayingWithCaptions()
            acknowledgeImmersiveHint()
            screenshot("01-playing")

            shell("input keyevent KEYCODE_HOME")
            awaitActivity("in picture-in-picture") { it.isInPictureInPictureMode }
            val entered = onMain { factory.current().currentPosition }
            await("playing on in the window") { it.isPlaying && it.currentPosition > entered + WINDOW_PLAY_MILLIS }
            await("position reported from the window") { factory.reported.any { position -> position > entered } }
            screenshot("02-window-playing")

            val menu = discoverMenu()
            pressUntil("paused from the window", menu.action) { !factory.current().playWhenReady }
            val paused = onMain { factory.current().currentPosition }
            SystemClock.sleep(PAUSE_HOLD_MILLIS)
            assertEquals(paused, onMain { factory.current().currentPosition })
            screenshot("03-window-paused")

            pressUntil("playing again from the window", menu.action) { factory.current().isPlaying }
            await("playing on in the window again") { it.currentPosition > paused + 1_000 }
            val beforeExpand = onMain { factory.current().currentPosition }

            pressUntil("expanded", menu.expand) { !activity.isInPictureInPictureMode }
            awaitActivity("resumed full screen") { it.lifecycle.currentState == Lifecycle.State.RESUMED }
            await("playing on after expanding") { it.isPlaying && it.currentPosition > beforeExpand }
            scenario.onActivity { assertSame(activity, it) }
            assertEquals(1, factory.created)
            SystemClock.sleep(SETTLE_MILLIS)
            screenshot("04-expanded")
            // The controls show where playback is after the round trip.
            val (width, height) = onMain { activity.window.decorView.run { width to height } }
            shell("input tap ${width / 2} ${height / 2}")
            SystemClock.sleep(SETTLE_MILLIS)
            screenshot("05-expanded-controls")
        } finally {
            scenario.close()
        }
    }

    @Test
    fun closingTheWindowStopsTheVideoAndTheNextVisitFindsItPaused() {
        val scenario = launchPlayer()
        try {
            awaitPlayingWithCaptions()
            acknowledgeImmersiveHint()
            shell("input keyevent KEYCODE_HOME")
            awaitActivity("in picture-in-picture") { it.isInPictureInPictureMode }
            val entered = onMain { factory.current().currentPosition }
            await("playing on in the window") { it.isPlaying && it.currentPosition > entered + WINDOW_PLAY_MILLIS }
            screenshot("10-window-playing")

            pressUntil("window closed", discoverMenu().close) {
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            val closedAt = onMain { factory.reported.last() }
            assertTrue(closedAt > entered + WINDOW_PLAY_MILLIS)
            SystemClock.sleep(SETTLE_MILLIS)
            screenshot("11-window-closed")

            // The launcher's own intent, which the scenario recognises as its Activity.
            val component = "${context.packageName}/${MainActivity::class.java.name}"
            shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $component")
            awaitActivity("reopened") { it.lifecycle.currentState == Lifecycle.State.RESUMED }
            await("the reopened video waits, paused") {
                factory.created == 2 && it.playbackState == Player.STATE_READY && !it.playWhenReady
            }
            assertEquals(closedAt.toDouble(), onMain { factory.current().currentPosition }.toDouble(), 1_000.0)
            scenario.onActivity { assertSame(activity, it) }
            SystemClock.sleep(SETTLE_MILLIS)
            screenshot("12-reopened-paused")
        } finally {
            scenario.close()
        }
    }

    private fun launchPlayer(): ActivityScenario<MainActivity> {
        factory = PictureInPictureProofPlayerFactory("pip-proof-${runId()}")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
        onMain {
            activity.setContent {
                PutioTheme {
                    MobilePlayerScreen(
                        state = localVideoState(),
                        onRetry = { error("Unexpected local video retry") },
                        onPlayerFailure = { failure, _ -> error("Local video failed: $failure") },
                        onBack = {},
                        playerFactory = factory,
                        subtitleStartupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true),
                    )
                }
            }
        }
        return scenario
    }

    private fun awaitPlayingWithCaptions() =
        await("video playing") {
            factory.renderedFrame && it.isPlaying && it.currentPosition > PLAYED_BEFORE_HOME_MILLIS &&
                it.currentCues.cues.isNotEmpty()
        }

    /**
     * Shows the system menu over the window and reads where its Pause, Expand and Close buttons sit. The
     * accessibility window list drops the menu once it has been used, so later presses reuse these
     * places and check the player's or the Activity's state instead.
     */
    private fun discoverMenu(): MenuButtons {
        val automation = interactiveAutomation()
        val deadline = SystemClock.uptimeMillis() + ACTION_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            automation.clearCache()
            val windows = automation.windows
            val menu = windows.firstOrNull {
                it.isInPictureInPictureMode && it.type == AccessibilityWindowInfo.TYPE_SYSTEM
            }?.root
            val action = menu?.findDescribed("Pause")
            val expand = menu?.findDescribed("Expand")
            val close = menu?.findDescribed("Close")
            if (action != null && expand != null && close != null) return MenuButtons(action, expand, close)
            if (menu == null) {
                // A tap away from the centre, where Expand appears, brings the menu up.
                windows.firstOrNull {
                    it.isInPictureInPictureMode && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }?.let { window ->
                    val bounds = Rect().also(window::getBoundsInScreen)
                    shell("input tap ${bounds.left + bounds.width() / 8} ${bounds.centerY()}")
                }
            }
            SystemClock.sleep(MENU_SETTLE_MILLIS)
        }
        error("The picture-in-picture menu never showed Pause, Expand and Close")
    }

    /** The first press may only bring a hidden menu back; the next one reaches the button. */
    private fun pressUntil(label: String, button: Rect, done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + ACTION_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            shell("input tap ${button.centerX()} ${button.centerY()}")
            val settled = SystemClock.uptimeMillis() + MENU_SETTLE_MILLIS
            while (SystemClock.uptimeMillis() < settled) {
                if (onMain(done)) return
                SystemClock.sleep(POLL_MILLIS)
            }
        }
        error("Timed out waiting for $label")
    }

    /** A fresh device teaches immersive mode once, over the video; acknowledge it if it shows. */
    private fun acknowledgeImmersiveHint() {
        val automation = interactiveAutomation()
        val deadline = SystemClock.uptimeMillis() + HINT_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            automation.windows.firstNotNullOfOrNull { window ->
                window.root?.findAccessibilityNodeInfosByText(IMMERSIVE_HINT_ACTION)?.firstOrNull()
            }?.let { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                return
            }
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun interactiveAutomation() =
        InstrumentationRegistry.getInstrumentation().uiAutomation.apply {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }

    private fun AccessibilityNodeInfo.findDescribed(description: String): Rect? {
        if (isVisibleToUser && contentDescription?.toString().equals(description, ignoreCase = true)) {
            return Rect().also(::getBoundsInScreen)
        }
        return (0 until childCount).firstNotNullOfOrNull { getChild(it)?.findDescribed(description) }
    }

    private fun await(label: String, predicate: (Player) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            val met = onMain {
                factory.player?.let {
                    check(it.playerError == null) { "Video failed: ${it.playerError?.errorCodeName}" }
                    predicate(it)
                } == true
            }
            if (met) return
            SystemClock.sleep(POLL_MILLIS)
        }
        val state = onMain {
            factory.player?.let {
                "playing=${it.isPlaying} state=${it.playbackState} position=${it.currentPosition} " +
                    "cues=${it.currentCues.cues.size} frame=${factory.renderedFrame} reported=${factory.reported}"
            }
        }
        error("Timed out waiting for $label: $state")
    }

    private fun awaitActivity(label: String, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain { predicate(activity) }) return
            SystemClock.sleep(POLL_MILLIS)
        }
        error("Timed out waiting for $label")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
    }

    private fun localVideoState() = run {
        // Asking for the directory first creates it with the app's ownership on a fresh install.
        val directory = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        val file = File(requireNotNull(arguments.getString("putio.pip.fixture"))).canonicalFile
        require(file.toPath().startsWith(directory.toPath())) { "The fixture must sit under $directory" }
        require(file.isFile && file.canRead()) { "Unreadable fixture $file" }
        // The SDK owns production URLs. This proof reads only its caller-owned local fixture.
        val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(Uri.fromFile(file).toString())
        playbackState(
            target = PlaybackTarget(FilesItemId(PROOF_FILE_ID), file.name, PlaybackMediaType.VIDEO),
            content = PlaybackContent.Ready(
                PlaybackSource(
                    fileId = PROOF_FILE_ID,
                    kind = PlaybackSourceKind.ORIGINAL,
                    url = url,
                    startFromSeconds = 0.0,
                    subtitles = PlaybackSubtitles.None,
                ),
            ),
            nextRequestValue = 1,
        )
    }

    private fun screenshot(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "pip-proof-${runId()}")
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

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.pip.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val PROOF_FILE_ID = 9_154_037L
        const val PLAYED_BEFORE_HOME_MILLIS = 3_000L
        const val WINDOW_PLAY_MILLIS = 3_000L
        const val PAUSE_HOLD_MILLIS = 2_000L
        const val SETTLE_MILLIS = 1_500L
        const val MENU_SETTLE_MILLIS = 1_500L
        const val ACTION_TIMEOUT_MILLIS = 30_000L
        const val HINT_TIMEOUT_MILLIS = 3_000L
        const val IMMERSIVE_HINT_ACTION = "Got it"
        const val AWAIT_TIMEOUT_MILLIS = 30_000L
        const val POLL_MILLIS = 200L
    }
}

/**
 * Production renderers and audio attributes over a local file, which the production player's
 * download-cache HTTP source cannot read; positions go through the real observer to a recorder.
 */
private class MenuButtons(val action: Rect, val expand: Rect, val close: Rect)

private class PictureInPictureProofPlayerFactory(private val lease: String) : MobilePlayerFactory {
    var player: Player? = null
        private set
    var created = 0
        private set
    var renderedFrame = false
        private set
    val reported = mutableListOf<Long>()

    fun current(): Player = checkNotNull(player)

    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        ExoPlayer.Builder(context, playbackRenderersFactory(context))
            .setAudioAttributes(mediaType.audioAttributes(), true)
            .build()
            .also {
                created += 1
                player = it
                renderedFrame = false
                it.addListener(
                    object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            renderedFrame = true
                        }
                    },
                )
            }

    override fun reportableItem(item: MediaItem, useStartFrom: Boolean): MediaItem = item.withReportingLease(lease)

    override fun observePositions(context: Context, player: Player): Closeable {
        val scope = MainScope()
        val observer = PlaybackPositionObserver(player, scope) { _, position -> reported += position }
        return Closeable {
            observer.close()
            scope.cancel()
        }
    }

    // This private-video proof must not stop an unrelated real audio session.
    override fun stopAudio(context: Context) = Unit
}
