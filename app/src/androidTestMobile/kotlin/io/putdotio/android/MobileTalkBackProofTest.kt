package io.putdotio.android

import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.android.playback.PlaybackState
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual TalkBack gestures; capture SpeechControllerImpl separately to verify spoken utterances. */
@RunWith(AndroidJUnit4::class)
class MobileTalkBackProofTest {
    @get:Rule val optIn = accessibilityProofOptIn()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val focused = CopyOnWriteArrayList<String>()
    private lateinit var automation: UiAutomation

    @Test
    fun talkBackActivatesAuthAndTraversesPersistentPlaybackControls() {
        // Compose/Espresso rules reconnect UiAutomation without this flag and suppress TalkBack.
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val manager = instrumentation.targetContext.getSystemService(AccessibilityManager::class.java)
        require(manager.isTouchExplorationEnabled) { "Enable TalkBack before running this selector" }
        require(manager.getEnabledAccessibilityServiceList(-1).any {
            it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback"
        }) { "This selector requires actual TalkBack" }
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED &&
                event.packageName?.toString() in
                setOf(instrumentation.targetContext.packageName, "com.android.systemui")
            ) {
                val description = event.contentDescription?.toString().orEmpty()
                val text = event.text.joinToString(" | ")
                val source = event.source
                focused += (listOf(description, text) + nodeLabels(source))
                    .filter(String::isNotBlank).distinct().joinToString(" | ")
            }
        }
        try {
            exerciseSurfaces()
        } finally {
            File(accessibilityProofDirectory(), "talkback-focus.txt").writeText(focused.joinToString("\n"))
            automation.setOnAccessibilityEventListener(null)
        }
    }

    private fun nodeLabels(node: AccessibilityNodeInfo?, depth: Int = 0): List<String> {
        if (node == null || depth > 3) return emptyList()
        return listOf(node.contentDescription?.toString().orEmpty(), node.text?.toString().orEmpty()) +
            (0 until minOf(node.childCount, 20)).flatMap { nodeLabels(node.getChild(it), depth + 1) }
    }

    private fun exerciseSurfaces() {
        val activations = AtomicInteger()
        val factory = TalkBackPlayerFactory()
        val state = localPlaybackState()
        var video by mutableStateOf(false)
        ActivityScenario.launch(MobileFullscreenProofActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent {
                    PutioTheme {
                        Surface(Modifier.fillMaxSize()) {
                            if (video) {
                                MobilePlayerScreen(
                                    state = state,
                                    onRetry = { error("Unexpected local video retry") },
                                    onPlayerFailure = { failure, _ -> error("Local video failed: $failure") },
                                    onBack = { video = false },
                                    playerFactory = factory,
                                )
                            } else {
                                MobileAuthMessageScreen(
                                    title = "Sign in could not finish",
                                    message = "The browser closed before authentication completed. " +
                                        "Your account is unchanged.",
                                    actionLabel = "Try signing in again",
                                    onAction = { activations.incrementAndGet() },
                                )
                            }
                        }
                    }
                }
            }
            await("Auth accessibility tree ready") { focused.isNotEmpty() }
            focusNext("Try signing in again")
            accessibilityProofScreenshot("talkback-auth-action", automation)
            doubleTap("Try signing in again")
            await("TalkBack auth activation") { activations.get() == 1 }
            scenario.onActivity { video = true }
            await("Local video ready") {
                var ready = false
                scenario.onActivity {
                    ready = factory.player?.let { it.playbackState == Player.STATE_READY && it.isPlaying } == true
                }
                ready
            }
            SystemClock.sleep(6_000)
            if (automation.windows.any { window ->
                    window.root?.let { root ->
                        root.packageName?.toString() == "com.android.systemui" &&
                            root.findAccessibilityNodeInfosByText("Got it").isNotEmpty()
                    } == true
                }
            ) {
                focusNext("Got it")
                doubleTap("Got it")
            }
            accessibilityProofScreenshot("talkback-playback-persistent", automation)
            focusNext("Pause")
            accessibilityProofScreenshot("talkback-playback-pause", automation)
            doubleTap("Pause")
            await("TalkBack paused video") {
                var paused = false
                scenario.onActivity { paused = factory.player?.playWhenReady == false }
                paused
            }
            focusNext("Speed (1×)")
            accessibilityProofScreenshot("talkback-playback-speed", automation)
            doubleTap("Speed (1×)")
            focusNext("1.5")
            accessibilityProofScreenshot("talkback-speed-choice", automation)
            doubleTap("1.5")
            await("TalkBack selected speed") {
                var selected = false
                scenario.onActivity { selected = factory.player?.playbackParameters?.speed == 1.5f }
                selected
            }
            focusNext("Audio", forward = false)
            accessibilityProofScreenshot("talkback-playback-audio", automation)
            focusNext("Captions")
            accessibilityProofScreenshot("talkback-playback-captions", automation)
            scenario.onActivity { video = false }
            focusNext("Try signing in again")
            scenario.onActivity { activity ->
                activity.setContent {
                    PutioTheme {
                        MobilePlayerScreen(
                            state = localPlaybackState(PlaybackMediaType.AUDIO),
                            onRetry = { error("Unexpected local audio retry") },
                            onPlayerFailure = { failure, _ -> error("Local audio failed: $failure") },
                            onBack = {},
                            playerFactory = factory,
                        )
                    }
                }
            }
            await("Local audio ready") {
                var ready = false
                scenario.onActivity {
                    ready = factory.player?.let { it.playbackState == Player.STATE_READY && it.isPlaying } == true
                }
                ready
            }
            focusNext("Pause")
            accessibilityProofScreenshot("talkback-audio-pause", automation)
            doubleTap("Pause")
            await("TalkBack paused audio") {
                var paused = false
                scenario.onActivity { paused = factory.player?.playWhenReady == false }
                paused
            }
            focusNext("Playback position", forward = false)
            accessibilityProofScreenshot("talkback-audio-position", automation)
            focusNext("Playback options")
            accessibilityProofScreenshot("talkback-audio-options", automation)
            doubleTap("Playback options")
            focusNext("Playback speed")
            accessibilityProofScreenshot("talkback-audio-speed", automation)
        }
    }

    private fun currentFocus(): String = nodeLabels(
        automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY),
    ).filter(String::isNotBlank).distinct().joinToString(" | ")

    private fun focusNext(label: String, forward: Boolean = true) {
        repeat(24) {
            val before = currentFocus()
            if (before.contains(label, ignoreCase = true)) return
            swipe(forward)
            val deadline = SystemClock.uptimeMillis() + 3_000
            while (currentFocus() == before && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        }
        error("TalkBack did not focus $label; visited ${focused.joinToString()}")
    }

    private fun swipe(forward: Boolean) = hardwareGesture(if (forward) "swipe-right" else "swipe-left")

    private fun doubleTap(label: String) {
        check(currentFocus().contains(label, ignoreCase = true)) { "TalkBack focus moved before activating $label" }
        hardwareGesture("double-tap")
    }

    private fun hardwareGesture(action: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        val id = UUID.randomUUID().toString()
        val directory = accessibilityProofDirectory()
        val pending = File(directory, "gesture-request.pending")
        val display = instrumentation.targetContext.getSystemService(DisplayManager::class.java).getDisplay(0)
        pending.writeText("$id $action $width $height ${display.rotation}")
        check(pending.renameTo(File(directory, "gesture-request.txt")))
        val acknowledged = File(directory, "gesture-$id.done")
        await("Host hardware bridge acknowledged $action") { acknowledged.isFile }
        SystemClock.sleep(500)
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue(description, predicate())
    }

    private fun localPlaybackState(mediaType: PlaybackMediaType = PlaybackMediaType.VIDEO): PlaybackState {
        val argument = if (mediaType == PlaybackMediaType.VIDEO) "video" else "audio"
        val path = requireNotNull(InstrumentationRegistry.getArguments().getString("putio.accessibility.$argument"))
        val file = File(path).canonicalFile
        require(file.isFile && file.canRead())
        val root = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)).canonicalFile
        require(file.toPath().startsWith(root.toPath()))
        // The SDK owns production URLs; this proof reads only the caller's local fixture.
        val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(Uri.fromFile(file).toString())
        return PlaybackState(
            PlaybackTarget(FilesItemId(9_147_001), "Accessibility local $argument", mediaType),
            PlaybackContent.Ready(PlaybackSource(9_147_001, PlaybackSourceKind.ORIGINAL, url,
                startFromSeconds = 20.0, subtitles = PlaybackSubtitles.Embedded)),
            nextRequestValue = 1,
        )
    }
}

private class TalkBackPlayerFactory : MobilePlayerFactory {
    var player: Player? = null
    override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Player =
        DefaultMobilePlayerFactory.create(context, mediaType).also { player = it }
}
