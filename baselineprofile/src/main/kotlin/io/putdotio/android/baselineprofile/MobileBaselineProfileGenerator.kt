package io.putdotio.android.baselineprofile

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the startup, tab, folder and scroll paths mobile users take, so ART compiles them ahead
 * of time. It reads Files only and plays nothing. `scripts/generate-baseline-profile.sh` runs it
 * and writes the result to `mobile/src/main/baseline-prof.txt`.
 */
@RunWith(AndroidJUnit4::class)
class MobileBaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        val arguments = InstrumentationRegistry.getArguments()
        val folder = requireNotNull(arguments.getString("putioProfileFolder")) { "Pass putioProfileFolder" }
        val child = requireNotNull(arguments.getString("putioProfileChild")) { "Pass putioProfileChild" }
        rule.collect(packageName = TARGET) {
            pressHome()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.text("Files")), TIMEOUT_MS)) {
                "No Files tab: sign nightly debug in with scripts/sign-in-devs-auto.sh first"
            }
            for (tab in TABS) {
                device.findObject(By.text(tab))?.click()
                device.waitForIdle()
            }
            startActivityAndWait(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://app.put.io/files/$folder")).setPackage(TARGET),
            )
            repeat(FOLDER_TRIPS) { device.openAndClose(child) }
            device.scrollList()
        }
    }

    private fun UiDevice.openAndClose(child: String) {
        val row = checkNotNull(wait(Until.findObject(By.text(child)), TIMEOUT_MS)) { "No row named $child" }
        row.click()
        waitForIdle()
        pressBack()
        waitForIdle()
    }

    private fun UiDevice.scrollList() {
        val list = checkNotNull(wait(Until.findObject(By.scrollable(true)), TIMEOUT_MS)) { "No scrollable list" }
        // Keeps the fling off the system gesture edges.
        list.setGestureMarginPercentage(GESTURE_MARGIN)
        repeat(FLINGS) { list.fling(Direction.DOWN) }
        repeat(FLINGS) { list.fling(Direction.UP) }
    }

    private companion object {
        const val TARGET = "io.put.putio.nightly.mobile.debug"
        const val TIMEOUT_MS = 10_000L
        const val FOLDER_TRIPS = 3
        const val FLINGS = 3
        const val GESTURE_MARGIN = 0.2f
        val TABS = listOf("History", "Transfers", "Downloads", "Account", "Files")
    }
}
