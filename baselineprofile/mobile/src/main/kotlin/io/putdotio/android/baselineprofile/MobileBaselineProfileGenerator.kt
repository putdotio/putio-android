package io.putdotio.android.baselineprofile

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MobileBaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun a_coldStartToWelcome() {
        rule.collect(
            packageName = PACKAGE_NAME,
            maxIterations = 1,
            includeInStartupProfile = false,
        ) {
            pressHome()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.desc(WORDMARK).pkg(PACKAGE_NAME)), CONTENT_TIMEOUT_MS)) {
                "The welcome screen never showed; generate on an emulator where the app is signed out"
            }
        }
        signInDevice()
    }

    @Test
    fun b_primaryLists() {
        rule.collect(
            packageName = PACKAGE_NAME,
            maxIterations = 1,
            includeInStartupProfile = false,
        ) {
            pressHome()
            startActivityAndWait()
            awaitFilesReady()
            scrollPrimaryList(required = true)
            PRIMARY_TABS.forEach { tab ->
                openTab(tab)
                scrollPrimaryList(required = tab.emptyLabel == null)
            }
            if (findTab(HISTORY_TAB.label) != null) {
                openTab(HISTORY_TAB)
                scrollPrimaryList(required = false)
            }
            openFilesTab()
        }
    }

    @Test
    fun c_storedTokenStartToFiles() {
        rule.collect(packageName = PACKAGE_NAME, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            awaitFilesReady()
        }
    }

    private fun signInDevice() {
        val started = device.executeShellCommand(
            "am start -W --activity-clear-task -n $PACKAGE_NAME/$DEBUG_SIGN_IN_ACTIVITY",
        )
        check("Error" !in started && "Exception" !in started) {
            "Failed to launch $DEBUG_SIGN_IN_ACTIVITY: $started"
        }
        val deadline = SystemClock.uptimeMillis() + SIGN_IN_TIMEOUT_MS
        var announced: String? = null
        while (!device.hasObject(By.textStartsWith(SIGNED_IN_PREFIX).pkg(PACKAGE_NAME))) {
            val stopped = device.findObject(By.textStartsWith(STOPPED_PREFIX).pkg(PACKAGE_NAME))?.text
            check(stopped == null) { stopped.orEmpty() }
            check(SystemClock.uptimeMillis() < deadline) {
                "Device sign-in never completed; approve the activation code in logcat tag $TAG"
            }
            val code = device.findObject(By.textStartsWith(CODE_PREFIX).pkg(PACKAGE_NAME))
                ?.text
                ?.removePrefix(CODE_PREFIX)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (code != null && code != announced) {
                Log.i(TAG, "activation code $code")
                announced = code
            }
            SystemClock.sleep(POLL_MS)
        }
        device.pressHome()
        device.executeShellCommand("am force-stop $PACKAGE_NAME")
        device.waitForIdle()
    }

    private fun awaitFilesReady() {
        check(device.wait(Until.hasObject(By.text(SEARCH_FILES).pkg(PACKAGE_NAME)), CONTENT_TIMEOUT_MS)) {
            "The signed-in Files screen never showed"
        }
        awaitContentSettled(FILES_TAB, FILES_ERROR, FILES_EMPTY)
    }

    private fun findTab(label: String): UiObject2? =
        device.findObjects(By.text(label).pkg(PACKAGE_NAME)).maxByOrNull { it.visibleBounds.bottom }

    private fun openFilesTab() {
        val tab = checkNotNull(findTab(FILES_TAB)) { "Navigation tab $FILES_TAB was not shown" }
        tab.click()
        awaitFilesReady()
    }

    private fun openTab(tabSpec: TabSpec) {
        val tab = checkNotNull(findTab(tabSpec.label)) { "Navigation tab ${tabSpec.label} was not shown" }
        val tabTop = tab.visibleBounds.top
        tab.click()
        check(awaitCondition(CONTENT_TIMEOUT_MS) { isTabOpen(tabSpec.label, tabTop) }) {
            "Navigation tab ${tabSpec.label} never opened"
        }
        awaitContentSettled(tabSpec.label, tabSpec.errorTitle, tabSpec.emptyLabel)
    }

    private fun awaitContentSettled(screen: String, errorTitle: String?, emptyLabel: String?) {
        check(
            awaitCondition(CONTENT_TIMEOUT_MS) {
                assertNoLoadError(screen, errorTitle)
                device.hasObject(By.scrollable(true).pkg(PACKAGE_NAME)) ||
                    (emptyLabel != null && device.hasObject(By.text(emptyLabel).pkg(PACKAGE_NAME)))
            },
        ) {
            "Content for $screen never settled"
        }
        assertNoLoadError(screen, errorTitle)
        device.waitForIdle()
    }

    private fun assertNoLoadError(screen: String, errorTitle: String?) {
        if (errorTitle != null && device.hasObject(By.text(errorTitle).pkg(PACKAGE_NAME))) {
            error("$screen showed error state: $errorTitle")
        }
        if (device.hasObject(By.text(OFFLINE_TITLE).pkg(PACKAGE_NAME))) {
            error("$screen went offline: $OFFLINE_TITLE")
        }
    }

    private fun isTabOpen(label: String, navTop: Int): Boolean {
        val selected = device.hasObject(By.text(label).selected(true).pkg(PACKAGE_NAME)) ||
            device.hasObject(By.selected(true).hasDescendant(By.text(label)).pkg(PACKAGE_NAME))
        val headerShown = device.findObjects(By.text(label).pkg(PACKAGE_NAME))
            .any { it.visibleBounds.bottom <= navTop }
        return selected || headerShown
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_MS)
        }
        return condition()
    }

    private fun scrollPrimaryList(required: Boolean) {
        val list = device.findObject(By.scrollable(true).pkg(PACKAGE_NAME))
        if (list == null) {
            check(!required) { "Expected a scrollable list on the current screen" }
            return
        }
        list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
        var scrolled = false
        repeat(SCROLL_STEPS) {
            if (list.scroll(Direction.DOWN, SCROLL_PERCENT)) {
                scrolled = true
            }
            device.waitForIdle()
        }
        if (scrolled) {
            repeat(SCROLL_STEPS) {
                list.scroll(Direction.UP, SCROLL_PERCENT)
                device.waitForIdle()
            }
        }
    }

    private data class TabSpec(
        val label: String,
        val errorTitle: String?,
        val emptyLabel: String?,
    )

    private companion object {
        const val PACKAGE_NAME = "io.put.putio"
        const val DEBUG_SIGN_IN_ACTIVITY = "io.putdotio.android.auth.DebugDeviceSignInActivity"
        const val TAG = "PutioBaselineProfile"
        const val WORDMARK = "put.io"
        const val SEARCH_FILES = "Search your files"
        const val FILES_TAB = "Files"
        const val FILES_EMPTY = "No files whatsoever!"
        const val FILES_ERROR = "Couldn’t load this"
        const val OFFLINE_TITLE = "put.io is out of reach"
        const val CODE_PREFIX = "Activation code "
        const val SIGNED_IN_PREFIX = "Signed in account "
        const val STOPPED_PREFIX = "Device sign-in stopped: "
        const val POLL_MS = 250L
        const val CONTENT_TIMEOUT_MS = 15_000L
        const val SIGN_IN_TIMEOUT_MS = 180_000L
        const val GESTURE_MARGIN_DIVISOR = 5
        const val SCROLL_STEPS = 3
        const val SCROLL_PERCENT = 0.8f
        val HISTORY_TAB = TabSpec("History", "Couldn’t load history", "No history yet")
        val PRIMARY_TABS = listOf(
            TabSpec("Transfers", "Couldn’t load transfers", "Nothing in the queue"),
            TabSpec("Downloads", null, "Nothing downloaded yet"),
            TabSpec("Account", "Couldn’t load account settings", null),
        )
    }
}
