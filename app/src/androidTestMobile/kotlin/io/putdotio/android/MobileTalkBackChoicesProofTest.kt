package io.putdotio.android

import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Observes actual UI callbacks while the host operates TalkBack without a second accessibility service. */
@RunWith(AndroidJUnit4::class)
class MobileTalkBackChoicesProofTest {
    @get:Rule val optIn = accessibilityProofOptIn()
    private val actions = ConcurrentLinkedQueue<String>()
    private lateinit var directory: File
    private var deadline = 0L

    @Test
    fun talkBackActivatesSignInResumeAndStartOver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(AccessibilityManager::class.java)
        require(manager.isTouchExplorationEnabled) { "Enable TalkBack before this selector" }
        require(manager.getEnabledAccessibilityServiceList(-1).any {
            it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback"
        }) { "Actual TalkBack is required" }
        directory = accessibilityProofDirectory()
        require(File(directory, "talkback-choices-stage.txt").createNewFile()) { "Use a fresh proof run ID" }
        // Three host phases at their documented limit, with launch slack.
        deadline = SystemClock.uptimeMillis() + 400_000
        var surface by mutableStateOf(ChoiceSurface.SignIn)
        ActivityScenario.launch(MobileFullscreenProofActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent {
                    PutioTheme {
                        Surface(Modifier.fillMaxSize()) {
                            key(surface) {
                                if (surface == ChoiceSurface.SignIn) {
                                    MobileSignedOutScreen(
                                        reason = null,
                                        canSignIn = true,
                                        onSignIn = { actions.add("sign-in") },
                                    )
                                } else {
                                    MobileResumePlaybackDialog(
                                        title = if (surface == ChoiceSurface.Resume) "Rehearsal video.mp4"
                                            else "Rehearsal audio.mp3",
                                        startFromSeconds = if (surface == ChoiceSurface.Resume) 83.0 else 42.0,
                                        onResume = { actions.add("resume") },
                                        onRestart = { actions.add("start-over") },
                                        onDismiss = { actions.add("dismiss") },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            awaitAction("sign-in", listOf("sign-in"))
            scenario.onActivity { surface = ChoiceSurface.Resume }
            awaitAction("resume", listOf("sign-in", "resume"))
            scenario.onActivity { surface = ChoiceSurface.StartOver }
            awaitAction("start-over", listOf("sign-in", "resume", "start-over"))
        }
        assertEquals(listOf("sign-in", "resume", "start-over"), actions.toList())
        File(directory, "talkback-choices-stage.txt").writeText("finished")
    }

    private fun awaitAction(phase: String, expected: List<String>) {
        File(directory, "talkback-choices-stage.txt").writeText(phase)
        val phaseDeadline = minOf(deadline, SystemClock.uptimeMillis() + 120_000)
        while (true) {
            val observed = actions.toList()
            File(directory, "talkback-choices-state.txt").writeText(observed.joinToString(","))
            assertEquals("Only the requested callback may fire during $phase", expected.take(observed.size), observed)
            if (observed == expected) return
            assertTrue(
                "Host completed $phase through actual TalkBack controls",
                SystemClock.uptimeMillis() < phaseDeadline,
            )
            SystemClock.sleep(250)
        }
    }
}

private enum class ChoiceSurface { SignIn, Resume, StartOver }
