package io.putdotio.android.baselineprofile

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TvBaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun linkToHome() {
        rule.collect(packageName = PACKAGE_NAME, maxIterations = 1) {
            pressHome()
            startActivityAndWait()
            check(awaitShell() != null) { "The app started signed in; generate on a fresh emulator" }
            device.wait(Until.hasObject(By.text(HOME_CONTENT)), CONTENT_TIMEOUT_MS)
        }
    }

    @Test
    fun listsScroll() {
        rule.collect(packageName = PACKAGE_NAME, maxIterations = 1) {
            pressHome()
            startActivityAndWait()
            awaitSession()
            repeat(MAX_MOVES) { device.pressDPadUp() }
            LIST_TAB_STEPS.forEach { steps ->
                repeat(steps) { device.pressDPadRight() }
                device.waitForIdle()
                repeat(SCROLL_MOVES) { device.pressDPadDown() }
                repeat(SCROLL_MOVES + MAX_MOVES) { device.pressDPadUp() }
            }
        }
    }

    @Test
    fun storedTokenStartToHome() {
        rule.collect(packageName = PACKAGE_NAME, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            awaitSession()
            device.wait(Until.hasObject(By.text(HOME_CONTENT)), CONTENT_TIMEOUT_MS)
        }
    }

    private fun awaitSession() {
        check(device.wait(Until.hasObject(By.text(ACCOUNT).pkg(PACKAGE_NAME)), CONTENT_TIMEOUT_MS)) {
            "The stored token did not restore the session"
        }
    }

    private fun awaitShell(): String? {
        val deadline = SystemClock.uptimeMillis() + SIGN_IN_TIMEOUT_MS
        var announced: String? = null
        while (!device.hasObject(By.text(ACCOUNT).pkg(PACKAGE_NAME))) {
            check(SystemClock.uptimeMillis() < deadline) { "The shell never showed; approve the activation code in logcat tag $TAG" }
            val code = device.findObject(By.descStartsWith(CODE_PREFIX))?.contentDescription?.removePrefix(CODE_PREFIX)
            if (code != null && code != announced) {
                Log.i(TAG, "activation code $code")
                announced = code
            }
            SystemClock.sleep(POLL_MS)
        }
        return announced
    }

    private fun select(text: String, move: () -> Boolean) {
        repeat(MAX_MOVES) {
            device.waitForIdle()
            if (focusedReads(text)) {
                device.pressDPadCenter()
                return
            }
            move()
        }
        error("D-pad never reached $text")
    }

    private fun focusedReads(text: String): Boolean =
        device.hasObject(By.focused(true).text(text)) || device.hasObject(By.focused(true).hasDescendant(By.text(text)))

    private companion object {
        const val PACKAGE_NAME = "io.put.putio"
        const val TAG = "PutioBaselineProfile"
        const val ACCOUNT = "Account"
        const val CODE_PREFIX = "Activation code "
        const val POLL_MS = 500L
        const val MAX_MOVES = 12
        const val CONTENT_TIMEOUT_MS = 15_000L
        const val SIGN_IN_TIMEOUT_MS = 180_000L
        const val SCROLL_MOVES = 30
        val LIST_TAB_STEPS = listOf(1, 2)
        val HOME_CONTENT: Pattern = Pattern.compile("Continue watching|Pinned|Nothing here yet\\.|Nothing pinned yet\\.")
    }
}
