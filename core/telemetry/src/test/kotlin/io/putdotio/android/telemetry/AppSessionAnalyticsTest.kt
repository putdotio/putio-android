package io.putdotio.android.telemetry

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppSessionAnalyticsTest {
    private val calls = mutableListOf<String>()
    private val session = AppSessionAnalytics(
        capture = { calls += it.name },
        onReturn = { calls += "recheck" },
        now = { 0L },
    )
    private val activity = Robolectric.buildActivity(Activity::class.java).get()

    @Test
    fun onlyAReturnRechecksConsentAndBeforeItsOpen() {
        foreground()
        background()
        foreground()

        assertEquals(listOf("app_opened", "app_backgrounded", "recheck", "app_opened"), calls)
    }

    private fun foreground() {
        session.onActivityStarted(activity)
        session.onActivityResumed(activity)
    }

    private fun background() {
        session.onActivityPaused(activity)
        session.onActivityStopped(activity)
    }
}
