package io.putdotio.android.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileBaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun coldStartToWelcome() {
        rule.collect(packageName = PACKAGE_NAME, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.desc(WORDMARK).pkg(PACKAGE_NAME)), CONTENT_TIMEOUT_MS)) {
                "The welcome screen never showed; generate on an emulator where the app is signed out"
            }
        }
    }

    private companion object {
        const val PACKAGE_NAME = "io.put.putio"
        const val WORDMARK = "put.io"
        const val CONTENT_TIMEOUT_MS = 15_000L
    }
}
