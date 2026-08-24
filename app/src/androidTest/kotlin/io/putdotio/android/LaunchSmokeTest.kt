package io.putdotio.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The harness launch proof: the shell reaches RESUMED, stays there through a
 * stability window, and actually composites pixels. `scripts/prove.sh` runs
 * this via `connectedAndroidTest`; a crash or ANR fails the instrumentation.
 *
 * The pixel assertion exists because a resumed activity can still render
 * black under emulator load; the shell's dark surface (#161616) composites
 * at mean luma ~22, so below 16 means nothing reached the screen.
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {

    @Test
    fun shellLaunchesStaysResumedAndRenders() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            // Stability window: a crash-restart or overlay steal within 3 s
            // is a failed launch even though the first RESUMED was real.
            Thread.sleep(3_000)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            // Bounded main-thread round-trip: RESUMED alone survives a
            // blocked main thread, and an unbounded runOnMainSync would hang
            // the instrumentation instead of failing it.
            val mainThreadResponsive = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { mainThreadResponsive.countDown() }
            assertTrue(
                "main thread unresponsive within 5s (ANR)",
                mainThreadResponsive.await(5, TimeUnit.SECONDS),
            )

            val screenshot = InstrumentationRegistry.getInstrumentation()
                .uiAutomation.takeScreenshot()
            assertTrue("uiAutomation returned no screenshot", screenshot != null)
            val luma = meanLuma(screenshot)
            assertTrue("screen is effectively black (mean luma $luma)", luma >= 16.0)
        }
    }

    private fun meanLuma(bitmap: Bitmap): Double {
        val step = 16
        var sum = 0.0
        var count = 0
        for (x in 0 until bitmap.width step step) {
            for (y in 0 until bitmap.height step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sum += 0.299 * r + 0.587 * g + 0.114 * b
                count += 1
            }
        }
        return if (count > 0) sum / count else 0.0
    }
}
