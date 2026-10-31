package io.putdotio.android.tv

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.tv.auth.TvLinkPhase
import io.putdotio.android.tv.auth.TvLinkStop
import java.io.File
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Controlled-state captures of the sign-in and startup boards. No API calls. */
@RunWith(AndroidJUnit4::class)
class TvSignInProofTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("TV sign-in proof requires opt-in", arguments.getString("putio.tv.signin.enabled") == "true")
                runId()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Before
    fun leaveTouchMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @Test
    fun signIn() {
        capture("androidtv-rde2-01-sign-in", "Get a new code") {
            TvLinkScreen(phase = TvLinkPhase.AwaitingLink(CODE), sessionExpired = false, onRequestNewCode = {})
        }
    }

    @Test
    fun codeExpired() {
        capture("androidtv-st-s9-code-expired", "This code expired.") {
            TvLinkScreen(
                phase = TvLinkPhase.Stopped(TvLinkStop.CodeExpired, code = CODE),
                sessionExpired = false,
                onRequestNewCode = {},
            )
        }
    }

    @Test
    fun sessionExpired() {
        capture("androidtv-st-s8-session-expired", "Welcome back!") {
            TvLinkScreen(phase = TvLinkPhase.AwaitingLink(CODE), sessionExpired = true, onRequestNewCode = {})
        }
    }

    @Test
    fun checkingSession() {
        capture("androidtv-st-s7-session", "Checking your session") {
            TvStartupScreen("Checking your session")
        }
    }

    @Test
    fun offline() {
        capture("androidtv-st-s6-offline", "Try now") { TvOfflineScreen(onTryNow = {}) }
    }

    private fun capture(label: String, waitFor: String, content: @Composable () -> Unit) {
        compose.setContent { PutioTvTheme { content() } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(waitFor).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(STEP_MILLIS)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-signin-proof-${runId()}")
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

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.signin.runId")))

    private companion object {
        const val CODE = "FXWNPH"
        const val STEP_MILLIS = 400L
    }
}
